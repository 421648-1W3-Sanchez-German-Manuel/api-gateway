package ar.edu.utn.frc.tup.p4.apigateway.config;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.JwtProperties;
import ar.edu.utn.frc.tup.p4.apigateway.routing.PublicRouteMatcher;
import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
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
 * Step 1 of the pipeline - the ONLY chain that authenticates.
 *
 * <p>DEC-44 - {@code iss} and {@code exp} are ALWAYS validated, without a
 * flag, inside the decoder. Both validators are truly SYNCHRONOUS: they read
 * nothing from the network. Session validation ({@code sid} against Redis)
 * does NOT go here — it is {@code SessionGuard}, a reactive WebFilter — for
 * the reason documented in {@code SessionValidator}.
 *
 * <p>R3 - zero {@code hasRole}/{@code hasAuthority}: everything private is
 * {@code authenticated()} and nothing more. The role is checked by the
 * destination.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            JwtProperties props) {
        var validators = List.<OAuth2TokenValidator<Jwt>>of(
                new JwtTimestampValidator(),
                new IssuerValidator(props.expectedIssuer()));

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain chain(ServerHttpSecurity http, ReactiveJwtDecoder decoder,
                                  CookieOrHeaderBearerConverter bearerConverter) {
        return http
                // Decision 1 of the "Sesion en Cookies" spec: SameSite=Strict +
                // same origin (nginx :3000, no subdomains) already covers CSRF
                // for the real use of this app. It stays disabled on purpose,
                // not by neglect - it is reopened if one day front and Gateway
                // stop sharing an origin.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(ex -> ex
                        // The /api/*/public/** pattern is THE SAME one used by
                        // PublicRouteGuard (PublicRouteMatcher): two definitions
                        // of "public" diverge at the edges.
                        .pathMatchers(PublicRouteMatcher.API_PUBLIC_PATTERN).permitAll()
                        .pathMatchers("/.well-known/**").permitAll()
                        .pathMatchers("/actuator/health/**").permitAll()
                        .pathMatchers("/actuator/prometheus").permitAll()
                        .pathMatchers("/fallback/**").permitAll()
                        // The documentation: the spec, the screen and its static
                        // assets. Anonymous on purpose -you cannot ask for a
                        // token on the page that explains how to get it- and
                        // that is why it is ONE single line: springdoc hangs
                        // everything off /api/docs/** (see the springdoc block
                        // of application.yml). Opening the path does NOT open
                        // the endpoints: /api/users/** keeps falling into
                        // anyExchange().authenticated() as before.
                        .pathMatchers("/api/docs/**").permitAll()
                        // R3: NO hasRole/hasAuthority here. Everything private
                        // is authenticated() and nothing more. The role
                        // decision lives in the destination's @PreAuthorize.
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtDecoder(decoder))
                        .bearerTokenConverter(bearerConverter)
                        .authenticationEntryPoint(SecurityProblemHandlers.authenticationEntryPoint()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(SecurityProblemHandlers.authenticationEntryPoint())
                        .accessDeniedHandler(SecurityProblemHandlers.accessDeniedHandler()))
                .build();
    }
}
