package ar.edu.utn.frc.tup.p4.apigateway.ratelimit.impl;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.RateLimitProperties;
import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import ar.edu.utn.frc.tup.p4.apigateway.ratelimit.RateLimitKeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

/**
 * DEC-24 - the real IP comes from X-Forwarded-For, and only if the previous hop
 * is a trusted proxy. Taking plain `getRemoteAddress()` behind a load balancer
 * ALWAYS gives the balancer's IP: the limiter would treat the whole internet as
 * a single client. And trusting the header without checking the proxy lets
 * anyone invent an IP per request and evade the limit.
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
        String direct = remote == null ? "unknown" : remote.getAddress().getHostAddress();

        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff == null || xff.isBlank() || !isTrustedProxy(direct)) return direct;

        // The FIRST value is the original client; the rest are proxies.
        return xff.split(",")[0].trim();
    }

    private boolean isTrustedProxy(String ip) {
        // In the tests the previous hop is 127.0.0.1, which is on the list.
        return props.trustedProxies() != null
                && props.trustedProxies().stream().anyMatch(cidr -> matches(ip, cidr));
    }

    /** CIDR prefix comparison. Enough for /8, /12, /16 and /32. */
    private boolean matches(String ip, String cidr) {
        String base = cidr.split("/")[0];
        int bits = Integer.parseInt(cidr.split("/")[1]);
        int octets = bits / 8;
        String[] a = ip.split("\\.");
        String[] b = base.split("\\.");
        if (a.length != 4 || b.length != 4) return false;
        for (int i = 0; i < octets; i++) if (!a[i].equals(b[i])) return false;
        return true;
    }

    private String header(ServerWebExchange exchange, String name) {
        String v = exchange.getRequest().getHeaders().getFirst(name);
        return v == null ? "unknown" : v;
    }
}
