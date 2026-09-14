package ar.edu.utn.frc.tup.p4.apigateway.config;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.JwtProperties;
import ar.edu.utn.frc.tup.p4.apigateway.routing.PublicRouteMatcher;
import ar.edu.utn.frc.tup.p4.apigateway.security.IssuerValidator;
import ar.edu.utn.frc.tup.p4.apigateway.web.SecurityProblemHandlers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.util.List;

/**
 * Paso 1 del pipeline - la UNICA cadena que autentica.
 *
 * <p>DEC-44 - {@code iss} y {@code exp} se validan SIEMPRE, sin flag, dentro
 * del decoder. Los dos validators son SINCRONICOS de verdad: no leen nada de
 * red. La validacion de sesion ({@code sid} contra Redis) NO va aca -es
 * {@code SessionGuard}, un WebFilter reactivo- por el motivo que documenta
 * {@code SessionValidator}.
 *
 * <p>R3 - cero {@code hasRole}/{@code hasAuthority}: todo lo privado es
 * {@code authenticated()} y nada mas. El rol lo chequea el destino.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            JwtProperties props) {
        var validadores = List.<OAuth2TokenValidator<Jwt>>of(
                new JwtTimestampValidator(),
                new IssuerValidator(props.expectedIssuer()));

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validadores));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain chain(ServerHttpSecurity http, ReactiveJwtDecoder decoder) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)      // API stateless, sin cookies
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(ex -> ex
                        // El patron de /api/*/public/** es EL MISMO que usa
                        // PublicRouteGuard (PublicRouteMatcher): dos
                        // definiciones de "publico" divergen en los bordes.
                        .pathMatchers(PublicRouteMatcher.API_PUBLIC_PATTERN).permitAll()
                        .pathMatchers("/.well-known/**").permitAll()
                        .pathMatchers("/actuator/health/**").permitAll()
                        .pathMatchers("/fallback/**").permitAll()
                        // La documentacion: el spec, la pantalla y sus estaticos.
                        // Anonima a proposito -no se puede pedir el token en la
                        // pagina que explica como sacarlo- y por eso es UNA sola
                        // linea: springdoc cuelga todo de /api/docs/** (ver el
                        // bloque springdoc de application.yml). Abrir el path
                        // NO abre los endpoints: /api/users/** sigue cayendo en
                        // anyExchange().authenticated() como antes.
                        .pathMatchers("/api/docs/**").permitAll()
                        // R3: NO hasRole/hasAuthority here. Everything private
                        // is authenticated() and nothing more. The role
                        // decision lives in the destination's @PreAuthorize.
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtDecoder(decoder))
                        .authenticationEntryPoint(SecurityProblemHandlers.authenticationEntryPoint()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(SecurityProblemHandlers.authenticationEntryPoint())
                        .accessDeniedHandler(SecurityProblemHandlers.accessDeniedHandler()))
                .build();
    }
}
