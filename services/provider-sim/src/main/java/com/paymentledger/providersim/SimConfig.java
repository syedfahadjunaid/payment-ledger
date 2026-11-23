package com.paymentledger.providersim;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
public class SimConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Runs delayed outcomes and webhook retries. */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService simScheduler() {
        return Executors.newScheduledThreadPool(4);
    }

    /** API-key check for /v1/*, compared in constant time. (No Spring Security needed for one rule.) */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> apiKeyFilter(SimProperties props) {
        byte[] expected = ("Bearer " + props.apiKey()).getBytes(StandardCharsets.UTF_8);
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String header = request.getHeader("Authorization");
                byte[] presented = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
                if (!MessageDigest.isEqual(expected, presented)) {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"invalid API key\"}");
                    return;
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/v1/*");
        return registration;
    }
}
