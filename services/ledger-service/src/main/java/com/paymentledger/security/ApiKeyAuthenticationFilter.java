package com.paymentledger.security;

import com.paymentledger.merchant.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code Authorization: Bearer <token>} and, if the token is valid, marks the request as
 * authenticated:
 * <ul>
 *   <li>the admin token from {@code .env} gives ROLE_ADMIN;</li>
 *   <li>an active merchant API key gives ROLE_MERCHANT and a {@link MerchantPrincipal}.</li>
 * </ul>
 * An invalid or missing token leaves the request anonymous; {@code SecurityConfig} then answers
 * 401 for protected paths. Not a Spring bean on purpose: as a bean it would also be registered as
 * a plain servlet filter and run twice.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiKeyRepository apiKeys;
    private final byte[] adminTokenHash;

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeys, String adminToken) {
        this.apiKeys = apiKeys;
        this.adminTokenHash = ApiKeys.sha256(adminToken);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            authenticate(header.substring(BEARER.length()).trim()).ifPresent(authentication -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }

    private Optional<Authentication> authenticate(String token) {
        byte[] presentedHash = ApiKeys.sha256(token);
        // Constant-time comparison: the time taken doesn't reveal how many leading bytes matched.
        // (Comparing hashes also hides the token's length.)
        if (MessageDigest.isEqual(presentedHash, adminTokenHash)) {
            return Optional.of(UsernamePasswordAuthenticationToken.authenticated(
                    AdminPrincipal.INSTANCE, null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        }
        if (!token.startsWith(ApiKeys.PREFIX)) {
            return Optional.empty();
        }
        return apiKeys.findByKeyHashAndRevokedAtIsNull(HexFormat.of().formatHex(presentedHash))
                .map(key -> UsernamePasswordAuthenticationToken.authenticated(
                        new MerchantPrincipal(key.getMerchantId(), key.getId(), key.getKeyPrefix()),
                        null,
                        AuthorityUtils.createAuthorityList("ROLE_MERCHANT")));
    }

    /** Webhooks prove who sent them with an HMAC signature instead (see WebhookService). */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/webhooks/");
    }
}
