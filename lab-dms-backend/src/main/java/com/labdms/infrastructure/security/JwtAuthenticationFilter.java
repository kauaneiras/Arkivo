package com.labdms.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * Turns a bearer token into an authenticated principal.
 *
 * <p>Two kinds of token are accepted:
 * <ul>
 *   <li>the installation's own HS256 token, issued by {@code /auth/login};</li>
 *   <li>when SSO is enabled, an access token issued by the configured
 *       identity provider (Keycloak), verified against its published keys.</li>
 * </ul>
 * The two are told apart by the signing algorithm in the token header, so a
 * Keycloak token is never logged as an "invalid" local token and vice versa.
 *
 * <p>Whatever the token, the account must be ACTIVE: a deactivated account's
 * token stops working at once, not when it expires eight hours later.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService customUserDetailsService;
    private final SsoTokenAuthenticator sso;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                   CustomUserDetailsService customUserDetailsService,
                                   SsoTokenAuthenticator sso) {
        this.tokenProvider = tokenProvider;
        this.customUserDetailsService = customUserDetailsService;
        this.sso = sso;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt)) {
                UserPrincipal principal = null;
                if (tokenProvider.isLocalToken(jwt)) {
                    if (tokenProvider.validateToken(jwt)) {
                        String userId = tokenProvider.getUserIdFromToken(jwt);
                        principal = (UserPrincipal) customUserDetailsService.loadUserById(userId);
                    }
                } else if (sso.isEnabled()) {
                    principal = sso.authenticate(jwt);
                }

                if (principal != null && principal.isEnabled() && principal.isAccountNonLocked()) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else if (principal != null) {
                    logger.warn("Token refused: account " + principal.getUsername() + " is " + principal.getStatus());
                }
            }
        } catch (Exception ex) {
            logger.warn("Could not authenticate the request: " + ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
