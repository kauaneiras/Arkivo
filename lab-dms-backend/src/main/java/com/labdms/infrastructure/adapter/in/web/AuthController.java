package com.labdms.infrastructure.adapter.in.web;

import com.labdms.infrastructure.security.JwtTokenProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Login and Registration")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final com.labdms.infrastructure.adapter.out.persistence.repository.UserJpaRepository userRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public AuthController(AuthenticationManager authenticationManager, 
                          JwtTokenProvider tokenProvider,
                          com.labdms.infrastructure.adapter.out.persistence.repository.UserJpaRepository userRepository,
                          org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                          org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate user and get JWT token")
    public ResponseEntity<JwtAuthenticationResponse> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginRequest.username(),
                        loginRequest.password()
                )
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = tokenProvider.generateToken(authentication);

        com.labdms.infrastructure.security.UserPrincipal userDetails = (com.labdms.infrastructure.security.UserPrincipal) authentication.getPrincipal();
        java.util.List<String> roles = userDetails.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toList());

        UserInfoDto userInfo = new UserInfoDto(userDetails.getId(), userDetails.getUsername(), userDetails.getEmail(), userDetails.getFullName(), roles);

        return ResponseEntity.ok(new JwtAuthenticationResponse(jwt, userInfo));
    }

    /**
     * Quem está autenticado, agora — não quem estava quando entrou.
     *
     * <p>As permissões viajam na resposta do login e ficam guardadas no
     * navegador. Sem esta rota, uma permissão nova só chegaria a quem voltasse a
     * autenticar-se: toda a gente com sessão aberta veria o sistema recusar
     * ações que na verdade já pode fazer, ou oferecer ações que já não pode. O
     * ecrã lê isto ao carregar e passa a refletir o estado real.
     */
    @GetMapping("/me")
    @Operation(summary = "Current identity, roles, permissions and sectors")
    public ResponseEntity<UserInfoDto> me(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.labdms.infrastructure.security.UserPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        java.util.List<String> authorities = principal.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toList());

        // Os setores a que pertence viajam com a identidade.
        //
        // Sem eles o ecrã não consegue prever a decisão do servidor: a regra de
        // edição é "administrador, autor, OU membro do setor", e faltando a
        // última parte a interface teria de escolher entre oferecer botões que
        // vão ser recusados ou esconder botões que funcionariam. Nenhuma das
        // duas é aceitável, e adivinhar é como se chega a ambas.
        //
        // Não é informação sensível: é a lista dos setores da própria pessoa,
        // que ela já vê no seu perfil.
        java.util.List<java.util.UUID> groups = jdbcTemplate.queryForList(
                "SELECT group_id FROM document_group_members WHERE user_id = ?",
                java.util.UUID.class, principal.getId());

        return ResponseEntity.ok(new UserInfoDto(principal.getId(), principal.getUsername(),
                principal.getEmail(), principal.getFullName(), authorities, groups));
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user (request access)")
    public ResponseEntity<?> registerUser(@Valid @RequestBody RegisterRequest registerRequest) {
        if (userRepository.findByEmail(registerRequest.email()).isPresent()) {
            return ResponseEntity.badRequest().body(new MessageResponse("Error: Email is already taken!"));
        }

        com.labdms.infrastructure.adapter.out.persistence.entity.UserJpaEntity user = new com.labdms.infrastructure.adapter.out.persistence.entity.UserJpaEntity();
        user.setId(java.util.UUID.randomUUID());
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
    
    // Simplification for records in controller
    public record LoginRequest(String username, String password) {}
    public record RegisterRequest(String email, String fullName, String password) {}
    public record MessageResponse(String message) {}
    /**
     * Quem está autenticado, o que pode e a que setores pertence.
     *
     * <p>{@code roles} traz as <i>authorities</i> do Spring, que são papéis E
     * permissões juntos ('ROLE_ADMIN' ao lado de 'DOCUMENT_CREATE'). O nome é
     * herdado e ficou mais estreito que o conteúdo.
     */
    public record UserInfoDto(java.util.UUID id, String username, String email, String fullName,
                              java.util.List<String> roles, java.util.List<java.util.UUID> groups) {
        /** Compatibilidade com quem ainda constrói isto sem os setores. */
        public UserInfoDto(java.util.UUID id, String username, String email, String fullName,
                           java.util.List<String> roles) {
            this(id, username, email, fullName, roles, java.util.List.of());
        }
    }
    public record JwtAuthenticationResponse(String accessToken, String tokenType, UserInfoDto user) {
        public JwtAuthenticationResponse(String accessToken, UserInfoDto user) {
            this(accessToken, "Bearer", user);
        }
    }
}
