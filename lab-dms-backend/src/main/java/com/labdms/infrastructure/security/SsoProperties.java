package com.labdms.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single sign-on through an OpenID Connect provider — Keycloak in practice.
 *
 * <p>Everything is a property so that turning SSO on is a deployment decision,
 * not a rebuild. With {@code enabled=false} (the default) nothing here is
 * touched and the installation authenticates exactly as before.
 *
 * <pre>
 * labdms:
 *   sso:
 *     enabled: true
 *     issuer-uri: https://sso.lab.example/realms/laboratorio
 *     client-id: lab-dms
 *     roles-claim: realm_access.roles      # where the roles live in the token
 *     role-mapping:                        # provider role -> local role
 *       dms-admin: ROLE_ADMIN
 *       dms-editor: ROLE_EDITOR
 *     default-roles: [ROLE_VIEWER]         # when the token names no mapped role
 *     sync-roles: true                     # provider is the source of truth
 *     auto-provision: true                 # first sign-in creates the account
 * </pre>
 */
@ConfigurationProperties(prefix = "labdms.sso")
public class SsoProperties {

    private boolean enabled = false;
    /** The OIDC issuer; its discovery document gives the keys and endpoints. */
    private String issuerUri;
    /** Public client the browser uses (authorization code + PKCE). */
    private String clientId;
    /** Expected audience, when the provider sets one (optional). */
    private String audience;
    /** Dotted path to the array of roles inside the token. */
    private String rolesClaim = "realm_access.roles";
    /** Provider role name → local role name (ROLE_ADMIN, ROLE_EDITOR, ROLE_REVIEWER, ROLE_VIEWER). */
    private Map<String, String> roleMapping = new LinkedHashMap<>();
    /** Roles given to an SSO account whose token maps to nothing. */
    private List<String> defaultRoles = List.of("ROLE_VIEWER");
    /** Replace the account's local roles with the token's on every sign-in. */
    private boolean syncRoles = true;
    /** Create the account on first sign-in; otherwise unknown users are refused. */
    private boolean autoProvision = true;
    /** Claim holding the e-mail (also used as username). */
    private String emailClaim = "email";
    /** Claim holding the display name. */
    private String nameClaim = "name";
    /** Where the browser is sent after signing out of the provider (optional). */
    private String postLogoutRedirectUri;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getIssuerUri() { return issuerUri; }
    public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getRolesClaim() { return rolesClaim; }
    public void setRolesClaim(String rolesClaim) { this.rolesClaim = rolesClaim; }
    public Map<String, String> getRoleMapping() { return roleMapping; }
    public void setRoleMapping(Map<String, String> roleMapping) { this.roleMapping = roleMapping; }
    public List<String> getDefaultRoles() { return defaultRoles; }
    public void setDefaultRoles(List<String> defaultRoles) { this.defaultRoles = defaultRoles; }
    public boolean isSyncRoles() { return syncRoles; }
    public void setSyncRoles(boolean syncRoles) { this.syncRoles = syncRoles; }
    public boolean isAutoProvision() { return autoProvision; }
    public void setAutoProvision(boolean autoProvision) { this.autoProvision = autoProvision; }
    public String getEmailClaim() { return emailClaim; }
    public void setEmailClaim(String emailClaim) { this.emailClaim = emailClaim; }
    public String getNameClaim() { return nameClaim; }
    public void setNameClaim(String nameClaim) { this.nameClaim = nameClaim; }
    public String getPostLogoutRedirectUri() { return postLogoutRedirectUri; }
    public void setPostLogoutRedirectUri(String postLogoutRedirectUri) { this.postLogoutRedirectUri = postLogoutRedirectUri; }

    /** Keycloak's standard endpoints, derived from the issuer. */
    public String authorizationEndpoint() {
        return issuerUri == null ? null : issuerUri.replaceAll("/+$", "") + "/protocol/openid-connect/auth";
    }

    public String tokenEndpoint() {
        return issuerUri == null ? null : issuerUri.replaceAll("/+$", "") + "/protocol/openid-connect/token";
    }

    public String logoutEndpoint() {
        return issuerUri == null ? null : issuerUri.replaceAll("/+$", "") + "/protocol/openid-connect/logout";
    }
}
