package com.orderops.orders.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/internal/health",
                                                "/api/csrf",
                                                "/login",
                                                "/error",
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/**")
                                        .hasAnyRole("VIEWER", "OPERATOR")
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/orders",
                                                "/api/orders/*/retry")
                                        .hasRole("OPERATOR")
                                        .anyRequest()
                                        .denyAll())
                .formLogin(
                        form ->
                                form.successHandler(
                                                (request, response, authentication) ->
                                                        response.setStatus(204))
                                        .failureHandler(
                                                (request, response, exception) ->
                                                        response.sendError(401)))
                .logout(
                        logout ->
                                logout.logoutSuccessHandler(
                                        (request, response, authentication) ->
                                                response.setStatus(204)))
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .requestCache(cache -> cache.disable())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(
            PasswordEncoder encoder,
            @Value("${orderops.security.operator-password}") String operatorPassword,
            @Value("${orderops.security.viewer-password}") String viewerPassword) {
        if (operatorPassword.isBlank() || viewerPassword.isBlank()) {
            throw new IllegalArgumentException("Operator and viewer passwords must be configured");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername("operator")
                        .password(encoder.encode(operatorPassword))
                        .roles("OPERATOR")
                        .build(),
                User.withUsername("viewer")
                        .password(encoder.encode(viewerPassword))
                        .roles("VIEWER")
                        .build());
    }
}
