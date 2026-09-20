package ar.edu.utn.frc.tup.p4.apigateway.ratelimit.impl;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.RateLimitProperties;
import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import ar.edu.utn.frc.tup.p4.apigateway.ratelimit.RateLimitKeyResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * DEC-24 - the real IP comes from the hop headers, and only if the previous hop
 * is a trusted proxy. Taking plain `getRemoteAddress()` behind a load balancer
 * ALWAYS gives the balancer's IP: the limiter would treat the whole internet as
 * a single client. And trusting the header without checking the proxy lets
 * anyone invent an IP per request and evade the limit.
 *
 * <p>Checking the proxy is necessary and NOT sufficient, which is the bug this
 * class used to have. The two headers nginx sends are not equally trustworthy:
 *
 * <pre>
 *   X-Real-IP        $remote_addr               SET     - overwrites whatever
 *                                                         the client sent.
 *   X-Forwarded-For  $proxy_add_x_forwarded_for APPENDS - the client's value
 *                                                         survives, and FIRST.
 * </pre>
 *
 * <p>So {@code X-Forwarded-For[0]} is a value the CLIENT chose, even behind a
 * perfectly trustworthy proxy. Reading it gave a fresh bucket per request to
 * anyone who sent a different invented IP each time, which silently deleted the
 * limit on {@code /registration/**} - the one route whose only protection it is,
 * because it sends mail without authentication.
 */
@Component
public class PrincipalRateLimitKeyResolver implements RateLimitKeyResolver {

    private final RateLimitProperties props;

    public PrincipalRateLimitKeyResolver(RateLimitProperties props) { this.props = props; }

    @Override
    public String resolve(ServerWebExchange exchange, String keyType) {
        return switch (keyType) {
            case "USER"    -> "user:"    + header(exchange, IdentityHeaders.USER_ID);
            case "SERVICE" -> "service:" + header(exchange, IdentityHeaders.SERVICE_ID);
            default        -> "ip:"      + realIp(exchange);
        };
    }

    private String realIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        String direct = remote == null || remote.getAddress() == null
                ? "unknown" : remote.getAddress().getHostAddress();

        // Nobody but a trusted hop gets to tell us who the client is. Without
        // this, anyone reaching the gateway directly invents their own IP.
        if (!isTrustedProxy(direct)) {
            return direct;
        }

        HttpHeaders headers = exchange.getRequest().getHeaders();

        String realIp = lastValueOf(headers, "X-Real-IP");
        if (realIp != null) {
            return realIp;
        }

        return rightmostUntrustedHop(headers, direct);
    }

    /**
     * The LAST value, not the first: each proxy that sets this header replaces
     * the previous one, so the last is the one the NEAREST proxy wrote. A first
     * value can only be there because it was never overwritten - i.e. it came
     * from the client.
     */
    private String lastValueOf(HttpHeaders headers, String name) {
        List<String> values = headers.getOrEmpty(name);
        for (int i = values.size() - 1; i >= 0; i--) {
            String value = values.get(i).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Fallback for a deployment whose proxy does not set X-Real-IP: read the
     * chain from the RIGHT, skipping hops that are themselves trusted proxies.
     *
     * The rightmost entry is the peer the nearest proxy actually observed, so
     * it is the only one no client can choose; everything to its left is
     * hearsay copied from a header. Walking left-to-right is what made the
     * limit evadable.
     */
    private String rightmostUntrustedHop(HttpHeaders headers, String direct) {
        List<String> chains = headers.getOrEmpty("X-Forwarded-For");
        if (chains.isEmpty()) {
            return direct;
        }
        String[] hops = String.join(",", chains).split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !isTrustedProxy(hop)) {
                return hop;
            }
        }
        // Every hop is a trusted proxy: there is no client address to extract.
        return direct;
    }

    private boolean isTrustedProxy(String ip) {
        // In the tests the previous hop is 127.0.0.1, which is on the list.
        return props.trustedProxies() != null
                && props.trustedProxies().stream().anyMatch(cidr -> matches(ip, cidr));
    }

    /**
     * Real prefix comparison, at bit level: it works for /20, /22, /27, IPv6
     * and any mask, not only multiples of 8. The previous version compared
     * whole octets ({@code bits / 8}), so the default {@code 172.16.0.0/12}
     * behaved as a /8: any {@code 172.x.x.x} counted as a trusted proxy.
     * Malformed CIDR or IP -> not trusted (fail-closed: X-Forwarded-For is
     * ignored).
     */
    boolean matches(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                return false;
            }
            int bits = Integer.parseInt(parts[1].trim());
            byte[] network = InetAddress.getByName(parts[0].trim()).getAddress();
            byte[] address = InetAddress.getByName(ip.trim()).getAddress();
            if (network.length != address.length || bits < 0 || bits > network.length * 8) {
                return false;
            }
            int fullOctets = bits / 8;
            for (int i = 0; i < fullOctets; i++) {
                if (network[i] != address[i]) {
                    return false;
                }
            }
            int remainder = bits % 8;
            if (remainder > 0) {
                int mask = (0xFF << (8 - remainder)) & 0xFF;
                if (((network[fullOctets] & 0xFF) & mask) != ((address[fullOctets] & 0xFF) & mask)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String header(ServerWebExchange exchange, String name) {
        String v = exchange.getRequest().getHeaders().getFirst(name);
        return v == null ? "unknown" : v;
    }
}
