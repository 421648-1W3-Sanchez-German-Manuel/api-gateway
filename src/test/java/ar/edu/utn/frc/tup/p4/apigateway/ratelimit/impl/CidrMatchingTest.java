package ar.edu.utn.frc.tup.p4.apigateway.ratelimit.impl;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.RateLimitProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CidrMatchingTest {

    private final PrincipalRateLimitKeyResolver resolver =
            new PrincipalRateLimitKeyResolver(new RateLimitProperties(true, List.of(), List.of()));

    @ParameterizedTest(name = "{0} en {1} -> {2}")
    @CsvSource({
            // el caso que rompia al matcher de octetos: /12 NO es /8
            "172.16.5.4,     172.16.0.0/12,   true",
            "172.31.255.1,   172.16.0.0/12,   true",
            "172.15.0.1,     172.16.0.0/12,   false",
            "172.99.0.1,     172.16.0.0/12,   false",
            // mascaras no multiplo de 8
            "10.1.20.30,     10.1.16.0/20,    true",
            "10.1.32.1,      10.1.16.0/20,    false",
            "192.168.1.65,   192.168.1.64/27, true",
            "192.168.1.95,   192.168.1.64/27, true",
            "192.168.1.96,   192.168.1.64/27, false",
            // bordes clasicos
            "127.0.0.1,      127.0.0.1/32,    true",
            "127.0.0.2,      127.0.0.1/32,    false",
            "10.9.8.7,       10.0.0.0/8,      true",
            // IPv6
            "fd00::1,        fd00::/64,       true",
            "fd00::ffff,     fd00::/64,       true",
            "fd01::1,        fd00::/64,       false",
            // malformadas: fail-closed
            "10.0.0.1,       no-es-cidr,      false",
            "10.0.0.1,       10.0.0.0/33,     false",
            "no-es-ip,       10.0.0.0/8,      false",
            "10.0.0.1,       ::1/128,         false",
    })
    void matchea_subredes_reales_y_falla_cerrado(String ip, String cidr, boolean esperado) {
        assertThat(resolver.matches(ip.trim(), cidr.trim())).isEqualTo(esperado);
    }
}
