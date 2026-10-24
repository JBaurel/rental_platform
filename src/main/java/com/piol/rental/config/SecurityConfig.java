package com.piol.rental.config;

import com.piol.rental.common.tenant.AccountContextFilter;
import com.piol.rental.user.service.AccessService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Sécurité : API stateless protégée par JWT (access token en en-tête Bearer).
 * Refus par défaut ; seules les routes listées sont publiques.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            AccessService accessService,
                                            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
            throws Exception {

        // Les erreurs 401/403 produites par Spring Security passent par GlobalExceptionHandler
        // pour garder le même format JSON (ApiError) que le reste de l'API.
        AuthenticationEntryPoint entryPoint = (request, response, ex) ->
                resolver.resolveException(request, response, null, ex);
        AccessDeniedHandler deniedHandler = (request, response, ex) ->
                resolver.resolveException(request, response, null, ex);

        // Le claim "role" du JWT (USER / ADMIN) devient l'autorité ROLE_USER / ROLE_ADMIN
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/api/v1/ping", "/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                // Statut du compte + résolution de l'espace (X-Owner-Id), juste après la lecture du JWT.
                // Instancié ici et non déclaré @Bean : sinon Spring Boot l'enregistrerait une 2e fois.
                .addFilterAfter(new AccountContextFilter(accessService, resolver), BearerTokenAuthenticationFilter.class);

        return http.build();
    }
}