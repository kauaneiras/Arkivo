package com.labdms.infrastructure.security;

import com.labdms.domain.model.user.User;
import com.labdms.domain.model.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Verifies an identity provider's access token and maps it to a local account.
 *
 * <p>The local account is the point. Every rule in the system — who wrote a
 * document, who may approve it, which sector someone belongs to — is keyed on
 * a row in {@code users}. SSO replaces the password check, not the account:
 * on first sign-in the account is created from the token (just-in-time
 * provisioning), and on every sign-in its roles are refreshed from the token
 * so that removing someone from a Keycloak group takes effect at once.
 *
 * <p>The decoder is built lazily from the issuer's discovery document, so the
 * application still starts when the provider is unreachable — SSO sign-ins
 * fail with a clear message until it is back, local sign-ins are unaffected.
 */
@Component
@EnableConfigurationProperties(SsoProperties.class)
public class SsoTokenAuthenticator {

    private static final Logger logger = LoggerFactory.getLogger(SsoTokenAuthenticator.class);

    private final SsoProperties props;
    private final JdbcTemplate jdbcTemplate;
    private final CustomUserDetailsService userDetailsService;
    private volatile JwtDecoder decoder;

    public SsoTokenAuthenticator(SsoProperties props, JdbcTemplate jdbcTemplate,
                                 CustomUserDetailsService userDetailsService) {
        this.props = props;
        this.jdbcTemplate = jdbcTemplate;
        this.userDetailsService = userDetailsService;
        if (props.isEnabled()) {
            logger.info("SSO enabled: issuer {} client {}", props.getIssuerUri(), props.getClientId());
        }
    }

    public boolean isEnabled() {
        return props.isEnabled() && props.getIssuerUri() != null && !props.getIssuerUri().isBlank();
    }

    public SsoProperties properties() {
        return props;
    }

    /**
     * @return the local principal for the token's subject, or null when the
     *         token is not valid for this installation
     */
    @Transactional
    public UserPrincipal authenticate(String token) {
        if (!isEnabled()) return null;
        Jwt jwt;
        try {
            jwt = decoder().decode(token);
        } catch (Exception e) {
            logger.warn("SSO token refused: {}", e.getMessage());
            return null;
        }

        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString(props.getEmailClaim());
        String name = jwt.getClaimAsString(props.getNameClaim());
        if (subject == null || subject.isBlank()) {
            logger.warn("SSO token has no subject; refused");
            return null;
        }
        if (email == null || email.isBlank()) {
            email = jwt.getClaimAsString("preferred_username");
        }
        if (email == null || email.isBlank()) {
            logger.warn("SSO token for subject {} carries no e-mail; refused", subject);
            return null;
        }
        if (name == null || name.isBlank()) name = email;

        List<String> localRoles = mapRoles(rolesIn(jwt));
        UUID userId = findOrProvision(subject, email.toLowerCase(), name, localRoles);
        if (userId == null) return null;

        return (UserPrincipal) userDetailsService.loadUserById(userId.toString());
    }

    /* ------------------------------------------------------------------ */

    private JwtDecoder decoder() {
        JwtDecoder d = decoder;
        if (d == null) {
            synchronized (this) {
                if (decoder == null) {
                    NimbusJwtDecoder built = JwtDecoders.fromIssuerLocation(props.getIssuerUri());
                    OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(props.getIssuerUri());
                    OAuth2TokenValidator<Jwt> audience = jwt -> {
                        if (props.getAudience() == null || props.getAudience().isBlank()) {
                            return OAuth2TokenValidatorResult.success();
                        }
                        List<String> aud = jwt.getAudience();
                        return aud != null && aud.contains(props.getAudience())
                                ? OAuth2TokenValidatorResult.success()
                                : OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error("invalid_token", "Audience mismatch", null));
                    };
                    built.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
                    decoder = built;
                }
                d = decoder;
            }
        }
        return d;
    }

    /** The roles array at the configured dotted path, e.g. realm_access.roles. */
    @SuppressWarnings("unchecked")
    private List<String> rolesIn(Jwt jwt) {
        Object node = jwt.getClaims();
        for (String part : props.getRolesClaim().split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) return List.of();
            node = map.get(part);
        }
        if (node instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        return List.of();
    }

    /** Provider roles → local roles, through the configured mapping. */
    private List<String> mapRoles(List<String> providerRoles) {
        Set<String> local = new LinkedHashSet<>();
        for (String r : providerRoles) {
            String mapped = props.getRoleMapping().get(r);
            if (mapped == null) {
                // A provider role that already uses a local name (ROLE_EDITOR)
                // maps to itself, so a realm can mirror the local vocabulary
                // without listing every pair.
                if (r.startsWith("ROLE_")) mapped = r;
            }
            if (mapped != null) local.add(mapped);
        }
        if (local.isEmpty()) local.addAll(props.getDefaultRoles());
        return new ArrayList<>(local);
    }

    private UUID findOrProvision(String subject, String email, String name, List<String> roles) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, status, auth_provider FROM users WHERE auth_provider = 'KEYCLOAK' AND external_subject = ?",
                subject);
        UUID userId;
        if (!rows.isEmpty()) {
            userId = (UUID) rows.get(0).get("id");
            jdbcTemplate.update("UPDATE users SET email = ?, username = ?, full_name = ?, last_login = ?, updated_at = ? WHERE id = ?",
                    email, email, name, LocalDateTime.now(), LocalDateTime.now(), userId);
        } else {
            // Same e-mail as a LOCAL account: link rather than duplicate, so a
            // laboratory migrating to SSO keeps authorship and history intact.
            List<Map<String, Object>> byEmail = jdbcTemplate.queryForList(
                    "SELECT id, auth_provider FROM users WHERE LOWER(email) = ?", email);
            if (!byEmail.isEmpty()) {
                userId = (UUID) byEmail.get(0).get("id");
                jdbcTemplate.update("""
                        UPDATE users SET auth_provider = 'KEYCLOAK', external_subject = ?, password_hash = NULL,
                                         full_name = ?, last_login = ?, updated_at = ?
                         WHERE id = ?
                        """, subject, name, LocalDateTime.now(), LocalDateTime.now(), userId);
                logger.info("Linked local account {} to SSO subject {}", email, subject);
            } else if (props.isAutoProvision()) {
                userId = UUID.randomUUID();
                jdbcTemplate.update("""
                        INSERT INTO users (id, username, email, full_name, password_hash, status,
                                           created_at, updated_at, last_login, auth_provider, external_subject)
                        VALUES (?, ?, ?, ?, NULL, 'ACTIVE', ?, ?, ?, 'KEYCLOAK', ?)
                        """, userId, email, email, name, LocalDateTime.now(), LocalDateTime.now(),
                        LocalDateTime.now(), subject);
                logger.info("Provisioned SSO account {} ({})", email, subject);
            } else {
                logger.warn("SSO subject {} ({}) has no local account and auto-provision is off", subject, email);
                return null;
            }
        }

        if (props.isSyncRoles() || rows.isEmpty()) {
            syncRoles(userId, roles);
        }
        return userId;
    }

    private void syncRoles(UUID userId, List<String> roles) {
        List<UUID> roleIds = new ArrayList<>();
        for (String role : roles) {
            List<UUID> ids = jdbcTemplate.queryForList("SELECT id FROM roles WHERE name = ?", UUID.class, role);
            if (ids.isEmpty()) {
                logger.warn("SSO role mapping names a role that does not exist locally: {}", role);
            } else {
                roleIds.add(ids.get(0));
            }
        }
        if (roleIds.isEmpty()) return;   // never strip every role because of a typo
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", userId);
        for (UUID roleId : roleIds) {
            jdbcTemplate.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    userId, roleId);
        }
    }

    /** Exposed for tests and the config endpoint. */
    static boolean isActive(User user) {
        return user != null && user.getStatus() == UserStatus.ACTIVE;
    }
}
