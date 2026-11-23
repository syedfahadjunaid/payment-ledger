package com.paymentledger.config;

import com.paymentledger.merchant.ApiKeyRepository;
import com.paymentledger.security.ApiKeyAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * All HTTP security rules live in this one class, so they are easy to review.
 *
 * <ul>
 *   <li>{@code /api/**}: merchants, with an API key ({@code Authorization: Bearer sk_test_...})</li>
 *   <li>{@code /admin/**}: operators, with the admin token from {@code .env}</li>
 *   <li>{@code /webhooks/**}: the payment provider; no key, the HMAC signature is checked instead</li>
 *   <li>anything else: denied</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiKeyRepository apiKeys, LedgerProperties props)
            throws Exception {
        http
                // API keys are not sent automatically by browsers, so CSRF does not apply.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/webhooks/**").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasRole("MERCHANT")
                        // Actuator runs on the internal management port (8081), see application.yml
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, e) -> writeProblem(response, 401,
                                "Unauthorized", "A valid API key is required: Authorization: Bearer sk_test_..."))
                        .accessDeniedHandler((request, response, e) -> writeProblem(response, 403,
                                "Forbidden", "These credentials may not use this endpoint.")))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)))
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeys, props.adminToken()),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Security errors happen before Spring MVC, so we write the RFC 9457 body by hand. */
    private static void writeProblem(HttpServletResponse response, int status, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s"}""".formatted(title, status, detail));
    }
}
