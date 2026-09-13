package ar.edu.utn.frc.tup.p4.apigateway.ratelimit.impl;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.RateLimitProperties;
import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import ar.edu.utn.frc.tup.p4.apigateway.ratelimit.RateLimitKeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetAddress;
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

    /**
     * Comparacion de prefijo real, a nivel de bits: vale para /20, /22, /27,
     * IPv6 y cualquier mascara, no solo multiplos de 8. La version anterior
     * comparaba octetos enteros ({@code bits / 8}), asi que el default
     * {@code 172.16.0.0/12} funcionaba como un /8: cualquier {@code 172.x.x.x}
     * contaba como proxy de confianza. CIDR o IP malformada -> no es de
     * confianza (fail-closed: se ignora X-Forwarded-For).
     */
    boolean matches(String ip, String cidr) {
        try {
            String[] partes = cidr.split("/");
            if (partes.length != 2) {
                return false;
            }
            int bits = Integer.parseInt(partes[1].trim());
            byte[] red = InetAddress.getByName(partes[0].trim()).getAddress();
            byte[] dir = InetAddress.getByName(ip.trim()).getAddress();
            if (red.length != dir.length || bits < 0 || bits > red.length * 8) {
                return false;
            }
            int completos = bits / 8;
            for (int i = 0; i < completos; i++) {
                if (red[i] != dir[i]) {
                    return false;
                }
            }
            int resto = bits % 8;
            if (resto > 0) {
                int mask = (0xFF << (8 - resto)) & 0xFF;
                if (((red[completos] & 0xFF) & mask) != ((dir[completos] & 0xFF) & mask)) {
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
