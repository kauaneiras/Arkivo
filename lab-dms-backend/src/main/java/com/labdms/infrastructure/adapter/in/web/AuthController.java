package com.labdms.infrastructure.adapter.in.web;

import com.labdms.infrastructure.security.JwtTokenProvider;
import com.labdms.infrastructure.security.SsoProperties;
import com.labdms.infrastructure.security.SsoTokenAuthenticator;
import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Login, registration and single sign-on")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final com.labdms.infrastructure.adapter.out.persistence.repository.UserJpaRepository userRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final SsoTokenAuthenticator sso;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtTokenProvider tokenProvider,
                          com.labdms.infrastructure.adapter.out.persistence.repository.UserJpaRepository userRepository,
                          org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                          JdbcTemplate jdbcTemplate,
                          SsoTokenAuthenticator sso) {
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.sso = sso;
    }

    /**
     * What the login screen needs to know before anyone signs in.
     *
     * <p>Public on purpose: it carries no secret (a public OIDC client has
     * none) and the screen has to decide whether to show the SSO button
     * before there is a session.
     */
    public record AuthConfigDto(boolean ssoEnabled, boolean localLoginEnabled, String issuerUri,
                                String clientId, String authorizationEndpoint, String tokenEndpoint,
                                String logoutEndpoint, String providerName) {}

    @GetMapping("/config")
    @Operation(summary = "Authentication options available to the login screen")
    public ResponseEntity<AuthConfigDto> config() {
        SsoProperties p = sso.properties();
        boolean enabled = sso.isEnabled();
        return ResponseEntity.ok(new AuthConfigDto(enabled, true,
                enabled ? p.getIssuerUri() : null,
                enabled ? p.getClientId() : null,
                enabled ? p.authorizationEndpoint() : null,
                enabled ? p.tokenEndpoint() : null,
                enabled ? p.logoutEndpoint() : null,
                enabled ? "Keycloak" : null));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate user and get JWT token")
    public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {
        // An SSO-managed account has no local password: say so instead of
        // answering "invalid password" to someone who never had one.
        List<String> provider = jdbcTemplate.queryForList(
                "SELECT auth_provider FROM users WHERE LOWER(username) = LOWER(?) OR LOWER(email) = LOWER(?)",
                String.class, loginRequest.username(), loginRequest.username());
        if (!provider.isEmpty() && !"LOCAL".equals(provider.get(0))) {
            return ResponseEntity.status(400).body(new MessageResponse(
                    "Esta conta entra pelo login único (SSO). Use o botão \"Entrar com SSO\"."));
        }

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.username(), loginRequest.password()));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = tokenProvider.generateToken(authentication);

        UserPrincipal userDetails = (UserPrincipal) authentication.getPrincipal();
        jdbcTemplate.update("UPDATE users SET last_login = NOW() WHERE id = ?", userDetails.getId());

        return ResponseEntity.ok(new JwtAuthenticationResponse(jwt, identityOf(userDetails)));
    }

    /**
     * Who is authenticated, now — not who was when they signed in.
     *
     * <p>Permissions and sectors travel with the identity so the screen can
     * predict the server's decisions (edit rule: administrator, author OR
     * member of the sector) instead of guessing.
     */
    @GetMapping("/me")
    @Operation(summary = "Current identity, roles, permissions and sectors")
    public ResponseEntity<UserInfoDto> me(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(identityOf(principal));
    }

    private UserInfoDto identityOf(UserPrincipal principal) {
        List<String> authorities = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
        List<UUID> groups = jdbcTemplate.queryForList(
                "SELECT group_id FROM document_group_members WHERE user_id = ?",
                UUID.class, principal.getId());
        List<String> provider = jdbcTemplate.queryForList(
                "SELECT auth_provider FROM users WHERE id = ?", String.class, principal.getId());
        return new UserInfoDto(principal.getId(), principal.getUsername(),
                principal.getEmail(), principal.getFullName(), authorities, groups,
                provider.isEmpty() ? "LOCAL" : provider.get(0));
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user (request access)")
    public ResponseEntity<?> registerUser(@Valid @RequestBody RegisterRequest registerRequest) {
        if (registerRequest.email() == null || registerRequest.email().isBlank()
                || registerRequest.password() == null || registerRequest.password().length() < 6
                || registerRequest.fullName() == null || registerRequest.fullName().isBlank()) {
            return ResponseEntity.badRequest().body(new MessageResponse(
                    "Nome, e-mail e senha (mínimo 6 caracteres) são obrigatórios."));
        }
        if (userRepository.findByEmail(registerRequest.email()).isPresent()) {
            return ResponseEntity.badRequest().body(new MessageResponse("Error: Email is already taken!"));
        }

        var user = new com.labdms.infrastructure.adapter.out.persistence.entity.UserJpaEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(registerRequest.email());
        user.setEmail(registerRequest.email());
        user.setFullName(registerRequest.fullName());
        user.setPasswordHash(passwordEncoder.encode(registerRequest.password()));
        user.setStatus("PENDING");
        user.setCreatedAt(java.time.LocalDateTime.now());
        user.setUpdatedAt(java.time.LocalDateTime.now());

        userRepository.save(user);

        return ResponseEntity.ok(new MessageResponse("User registered successfully! Awaiting admin approval."));
    }

    public record LoginRequest(String username, String password) {}
    public record RegisterRequest(String email, String fullName, String password) {}
    public record MessageResponse(String message) {}

    /**
     * Who is authenticated, what they can do, and which sectors they belong to.
     *
     * <p>{@code roles} carries Spring's <i>authorities</i>: roles AND
     * permissions together ('ROLE_ADMIN' next to 'DOCUMENT_CREATE').
     * {@code authProvider} tells the screen whether "change password" applies.
     */
    public record UserInfoDto(UUID id, String username, String email, String fullName,
                              List<String> roles, List<UUID> groups, String authProvider) {
        public UserInfoDto(UUID id, String username, String email, String fullName, List<String> roles) {
            this(id, username, email, fullName, roles, List.of(), "LOCAL");
        }
    }

    public record JwtAuthenticationResponse(String accessToken, String tokenType, UserInfoDto user) {
        public JwtAuthenticationResponse(String accessToken, UserInfoDto user) {
            this(accessToken, "Bearer", user);
        }
    }
}
