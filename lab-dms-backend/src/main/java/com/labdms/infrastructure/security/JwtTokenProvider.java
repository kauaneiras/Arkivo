package com.labdms.infrastructure.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import java.security.Key;
import java.util.Date;
import com.labdms.infrastructure.config.JwtConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final JwtConfig jwtConfig;

    public JwtTokenProvider(JwtConfig jwtConfig) {
        this.jwtConfig = jwtConfig;
    }

    public String generateToken(Authentication authentication) {
        UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtConfig.getExpirationMs());

        return Jwts.builder()
                .subject(userPrincipal.getId().toString())
                .claim("username", userPrincipal.getUsername())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSignInKey(), Jwts.SIG.HS256)
                .compact();
    }

    public String getUserIdFromToken(String token) {
        return Jwts.parser()
                .verifyWith((javax.crypto.SecretKey) getSignInKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    /**
     * Whether this token was issued HERE, judged by its header alone.
     *
     * <p>Local tokens are HS256; an identity provider signs with an asymmetric
     * key (RS256/ES256). Looking at the header first means a Keycloak token is
     * routed to the SSO verifier instead of being rejected — and logged — as a
     * forged local one.
     */
    public boolean isLocalToken(String token) {
        try {
            int dot = token.indexOf('.');
            if (dot <= 0) return true;
            String header = new String(Decoders.BASE64URL.decode(token.substring(0, dot)),
                    java.nio.charset.StandardCharsets.UTF_8);
            return header.contains("\"HS256\"") || header.contains("\"HS384\"") || header.contains("\"HS512\"");
        } catch (Exception e) {
            return true;
        }
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith((javax.crypto.SecretKey) getSignInKey()).build().parseSignedClaims(token);
            return true;
        } catch (MalformedJwtException e) {
            logger.error("Invalid JWT token: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            logger.error("JWT token is expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            logger.error("JWT token is unsupported: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("JWT claims string is empty: {}", e.getMessage());
        }
        return false;
    }

    private javax.crypto.SecretKey getSignInKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtConfig.getSecret());
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
