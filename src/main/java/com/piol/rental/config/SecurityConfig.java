package com.piol.rental.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
 
/**
 * Configuration de sécurité TEMPORAIRE (avant l'ajout du JWT, Phase 2).
 * Principe : tout est refusé par défaut, seules quelques routes sont publiques.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
 
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // API stateless authentifiée par jeton (pas de cookie de session) : CSRF sans objet
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/api/v1/ping").permitAll()
                        .anyRequest().authenticated())
                // 401 (et non 403) quand l'appelant n'est pas authentifié
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
