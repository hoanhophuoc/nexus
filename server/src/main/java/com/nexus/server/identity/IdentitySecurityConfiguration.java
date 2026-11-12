package com.nexus.server.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class IdentitySecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionAuthenticationFilter sessionFilter)
            throws Exception {
        return http
                .csrf(csrf -> csrf.spa())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/csrf",
                                "/api/v1/auth/password-reset", "/api/v1/auth/password-reset/confirm",
                                "/actuator/health", "/actuator/prometheus",
                                "/actuator/metrics", "/actuator/metrics/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, exception) -> response.sendError(401)))
                .addFilterBefore(sessionFilter, AnonymousAuthenticationFilter.class)
                .build();
    }
}
