package com.labdms.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Brute-force protection for the unauthenticated auth endpoints. Backed by Redis
 * (already a dependency) with a fixed-window counter, so the limit holds correctly
 * even if the backend is scaled to multiple replicas — an in-memory map would not.
 *
 * Login: 10 attempts / IP / 60s. Registration: 5 attempts / IP / 60s (cheaper to
 * abuse for enumeration/spam, so a tighter budget).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final int LOGIN_LIMIT = 10;
    private static final int REGISTER_LIMIT = 5;

    private final StringRedisTemplate redisTemplate;

    public RateLimitFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        int limit;
        String bucket;
        if ("POST".equalsIgnoreCase(request.getMethod()) && path.endsWith("/api/v1/auth/login")) {
            bucket = "login";
            limit = LOGIN_LIMIT;
        } else if ("POST".equalsIgnoreCase(request.getMethod()) && path.endsWith("/api/v1/auth/register")) {
            bucket = "register";
            limit = REGISTER_LIMIT;
        } else {
            filterChain.doFilter(request, response);
            return;
        }

        String key = "ratelimit:" + bucket + ":" + clientIp(request);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, WINDOW);
            }
            if (count != null && count > limit) {
                response.setStatus(429); // HttpStatus.TOO_MANY_REQUESTS
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"code\":\"RATE_LIMITED\",\"message\":\"Too many attempts. Try again in a minute.\"}");
                return;
            }
        } catch (Exception redisUnavailable) {
            // Fail open: an auth outage is worse than a temporarily unprotected
            // login endpoint. Redis being down is already surfaced by the health check.
            logger.warn("Rate limiter unavailable, allowing request through: " + redisUnavailable.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp;
        }
        return request.getRemoteAddr();
    }
}
