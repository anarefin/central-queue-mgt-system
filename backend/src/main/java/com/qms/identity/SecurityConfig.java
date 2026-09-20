package com.qms.identity;

import com.qms.platform.Profiles;
import com.qms.platform.realtime.RealtimeEndpoint;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.PermissionMatrix;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless resource server (API-010): no HTTP session, no per-request lookup, every request authenticated from the
 * signed JWT alone. Permissions are derived from the {@code roles} claim by the code-defined matrix and enforced with
 * method security at the service layer (API-016), so a second entry point cannot bypass them.
 */
@Configuration
@EnableMethodSecurity
@Profile(Profiles.SERVING)
class SecurityConfig {

    /** Paths reachable without a token. A build-time test checks these agree with the {@code @PublicEndpoint} markers. */
    static final String[] PUBLIC_PATHS = {
        "/api/v1/health/**",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/api/v1/auth/visitor/otp/request",
        "/api/v1/auth/visitor/otp/verify",
        "/api/v1/auth/visitor/refresh",
        "/api/v1/auth/visitor/logout",
        "/api/v1/devices/pair",
        "/api/v1/devices/refresh",
        "/api/v1/tickets/*/visitor",
        "/api/v1/tickets/*/visitor-cancel",
        "/api/v1/tickets/*/visitor/notification-opt-out",
        "/api/v1/tickets/*/push-subscription",
        "/api/v1/tickets/*/check-in",
        "/api/v1/tickets/*/delay",
        "/api/v1/notification-config/web-push-key"
    };

    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties, SigningKeyStore keys) {
        return JwtDecoderFactory.create(properties, keys);
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities());
        return converter;
    }

    private static Converter<Jwt, Collection<GrantedAuthority>> authorities() {
        return jwt -> {
            AuthenticatedUser user;
            try {
                user = AuthenticatedUser.from(jwt);
            } catch (IllegalArgumentException malformed) {
                throw new BadJwtException("Malformed subject, scope or role claim", malformed);
            }
            Set<GrantedAuthority> granted = new HashSet<>();
            user.roles().forEach(role -> granted.add(new SimpleGrantedAuthority("ROLE_" + role.wire().toUpperCase())));
            PermissionMatrix.authoritiesFor(user.roles()).forEach(a -> granted.add(new SimpleGrantedAuthority(a)));
            return granted;
        };
    }

    /**
     * The bearer token is the {@code Authorization} header, as everywhere. A browser cannot set a header on a WebSocket, so
     * for the hub's endpoint alone the token may instead ride in the {@code Sec-WebSocket-Protocol} offer, where it is
     * neither in a URL nor in a log (SRS §21.1).
     */
    @Bean
    BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
        return request -> {
            String token = header.resolve(request);
            return token != null ? token : RealtimeEndpoint.offeredToken(request).orElse(null);
        };
    }

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            JwtDecoder decoder,
            JwtAuthenticationConverter converter,
            BearerTokenResolver bearerTokens,
            ApiAuthenticationEntryPoint entryPoint,
            ApiAccessDeniedHandler accessDenied)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable) // bearer tokens in a header; the refresh cookie is SameSite=Strict
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // The hub's own handshake gates an anonymous ticket-secret visitor itself (RealtimeConfig.Authenticated,
                        // ticket 37, FR-SEC-033); it is not a @RestController, so it is kept out of PUBLIC_PATHS on purpose.
                        .requestMatchers(RealtimeEndpoint.PATH).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(bearerTokens)
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied));
        return http.build();
    }
}
