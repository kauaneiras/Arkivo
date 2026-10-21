package com.labdms.infrastructure.adapter.in.web;

import com.labdms.infrastructure.adapter.out.persistence.entity.RoleJpaEntity;
import com.labdms.infrastructure.adapter.out.persistence.entity.UserJpaEntity;
import com.labdms.infrastructure.adapter.out.persistence.repository.RoleJpaRepository;
import com.labdms.infrastructure.adapter.out.persistence.repository.UserJpaRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "User Management")
public class UserController {

    private final UserJpaRepository userRepository;
    private final RoleJpaRepository roleRepository;
    private final JdbcTemplate jdbcTemplate;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final com.labdms.application.service.NotificationService notificationService;

    public UserController(UserJpaRepository userRepository,
                          RoleJpaRepository roleRepository,
                          JdbcTemplate jdbcTemplate,
                          org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                          com.labdms.application.service.NotificationService notificationService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.notificationService = notificationService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a new user directly (Admin only)",
               description = "Creates an ACTIVE user with the given role, no approval step needed")
    public ResponseEntity<?> createUser(@RequestBody CreateUserRequest request) {
        if (request.email() == null || request.email().isBlank()
                || request.password() == null || request.password().length() < 6
                || request.fullName() == null || request.fullName().isBlank()) {
            return ResponseEntity.badRequest().body(new MessageDto("Invalid data: name, email and password (min 6 chars) are required"));
        }
        if (userRepository.findByEmail(request.email()).isPresent()) {
            return ResponseEntity.badRequest().body(new MessageDto("Email is already taken"));
        }

        RoleJpaEntity role = roleRepository.findByName(request.roleName()).orElse(null);
        if (role == null) {
            return ResponseEntity.badRequest().body(new MessageDto("Role not found"));
        }

        UserJpaEntity user = new UserJpaEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(request.email());
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus("ACTIVE");
        user.setCreatedAt(java.time.LocalDateTime.now());
        user.setUpdatedAt(java.time.LocalDateTime.now());
        userRepository.save(user);

        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                user.getId(), role.getId());

        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(new UserDto(user.getId(), user.getEmail(), user.getFullName(), user.getStatus(), role.getName()));
    }

    @PutMapping("/me/password")
    @Operation(summary = "Change own password",
               description = "Any authenticated user can change their own password by providing the current one")
    public ResponseEntity<?> changeOwnPassword(
            @RequestBody ChangePasswordRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.labdms.infrastructure.security.UserPrincipal principal) {

        if (request.newPassword() == null || request.newPassword().length() < 6) {
            return ResponseEntity.badRequest().body(new MessageDto("New password must have at least 6 characters"));
        }

        UserJpaEntity user = userRepository.findById(principal.getId()).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(new MessageDto("Current password is incorrect"));
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(java.time.LocalDateTime.now());
        userRepository.save(user);

        jdbcTemplate.update(
                "INSERT INTO audit_log (user_id, action, entity_type, entity_id, details, created_at) VALUES (?, 'PASSWORD_CHANGED', 'User', ?, '{}'::jsonb, NOW())",
                user.getId(), user.getId());

        return ResponseEntity.ok(new MessageDto("Password changed successfully"));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get all users (Admin only)")
    public ResponseEntity<List<UserDto>> getAllUsers(@RequestParam(required = false) String status) {
        // Roles are aggregated into one comma-separated value per user. Joining
        // user_roles directly would emit one ROW PER ROLE, so a user holding both
        // EDITOR and REVIEWER would appear twice in the list.
        String sql = "SELECT u.id, u.email, u.full_name, u.status, u.auth_provider, " +
                     "(SELECT string_agg(r.name, ',' ORDER BY r.name) " +
                     "   FROM user_roles ur JOIN roles r ON r.id = ur.role_id " +
                     "  WHERE ur.user_id = u.id) AS role_name " +
                     "FROM users u ";
        Object[] args = new Object[]{};
        if (status != null && !status.isEmpty()) {
            sql += " WHERE u.status = ? "; // parameter binding prevents SQL injection
            args = new Object[]{status};
        }
        sql += " ORDER BY u.full_name";

        List<UserDto> response = jdbcTemplate.query(sql, (rs, rowNum) -> new UserDto(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("full_name"),
            rs.getString("status"),
            rs.getString("role_name"),
            rs.getString("auth_provider")
        ), args);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Approve a pending user and assign a role")
    public ResponseEntity<?> approveUser(@PathVariable UUID id, @RequestBody ApproveRequest request) {
        UserJpaEntity user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        RoleJpaEntity role = roleRepository.findByName(request.roleName()).orElse(null);
        if (role == null) {
            return ResponseEntity.badRequest().body("Role not found");
        }

        user.setStatus("ACTIVE");
        user.setUpdatedAt(java.time.LocalDateTime.now());
        userRepository.save(user);

        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                user.getId(), role.getId());

        notificationService.notify(
                user.getId(),
                com.labdms.application.service.NotificationService.TYPE_USER_APPROVED,
                "Cadastro aprovado",
                "Seu cadastro foi aprovado com o papel " + role.getName() + ". Você já pode entrar no sistema.",
                "/dashboard"
        );

        return ResponseEntity.ok("User approved and role assigned successfully.");
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace a user's roles with a single one",
               description = "Kept for compatibility; prefer PUT /{id}/roles to assign several at once")
    public ResponseEntity<?> updateRole(@PathVariable UUID id, @RequestBody ApproveRequest request) {
        return updateRoles(id, new UpdateRolesRequest(List.of(request.roleName())));
    }

    /**
     * Assigns the full set of roles a user holds. Roles are cumulative: someone
     * can be EDITOR and REVIEWER at the same time (writes their own documents,
     * reviews other people's). Self-review is blocked separately, at approval
     * time, so holding both roles is safe.
     */
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Set all roles for a user (cumulative permissions)")
    public ResponseEntity<?> updateRoles(@PathVariable UUID id, @RequestBody UpdateRolesRequest request) {
        UserJpaEntity user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        if (request.roleNames() == null || request.roleNames().isEmpty()) {
            return ResponseEntity.badRequest().body(new MessageDto("At least one role is required"));
        }

        List<RoleJpaEntity> roles = new java.util.ArrayList<>();
        for (String name : request.roleNames()) {
            RoleJpaEntity role = roleRepository.findByName(name).orElse(null);
            if (role == null) {
                return ResponseEntity.badRequest().body(new MessageDto("Role not found: " + name));
            }
            roles.add(role);
        }

        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", user.getId());
        for (RoleJpaEntity role : roles) {
            jdbcTemplate.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    user.getId(), role.getId());
        }

        jdbcTemplate.update(
                "INSERT INTO audit_log (user_id, action, entity_type, entity_id, details, created_at) "
                        + "VALUES (?, 'USER_ROLES_CHANGED', 'User', ?, ?::jsonb, NOW())",
                user.getId(), user.getId(),
                "{\"roles\":\"" + String.join(",", request.roleNames()) + "\"}");

        return ResponseEntity.ok(new MessageDto("Roles updated: " + String.join(", ", request.roleNames())));
    }

    /** Users who may approve reviews — used to populate the "reviewer" picker. */
    @GetMapping("/reviewers")
    @Operation(summary = "List users who can review documents",
               description = "Active users holding a role with the REVIEW_APPROVE permission")
    public ResponseEntity<List<UserDto>> getReviewers() {
        List<UserDto> reviewers = jdbcTemplate.query("""
                SELECT DISTINCT u.id, u.email, u.full_name, u.status,
                       (SELECT string_agg(r2.name, ',') FROM user_roles ur2
                          JOIN roles r2 ON r2.id = ur2.role_id WHERE ur2.user_id = u.id) AS role_name
                  FROM users u
                  JOIN user_roles ur ON ur.user_id = u.id
                  JOIN role_permissions rp ON rp.role_id = ur.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                 WHERE p.code = 'REVIEW_APPROVE' AND u.status = 'ACTIVE'
                 ORDER BY u.full_name
                """, (rs, rowNum) -> new UserDto(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("full_name"),
                rs.getString("status"),
                rs.getString("role_name")));
        return ResponseEntity.ok(reviewers);
    }
    
    @GetMapping("/roles")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get all available roles")
    public ResponseEntity<List<RoleDto>> getRoles() {
        return ResponseEntity.ok(roleRepository.findAll().stream()
            .map(r -> new RoleDto(r.getId(), r.getName(), r.getDescription()))
            .collect(Collectors.toList()));
    }

    /* ------------------------- ativar / desativar ------------------------- */

    /**
     * Desativa ou reativa uma conta.
     *
     * <p>Desativar é a forma normal de tirar alguém do sistema: a conta perde o
     * acesso e o nome continua a aparecer em cada documento que escreveu e em
     * cada linha de auditoria que gerou. É o que a ISO 17025 exige — saber quem
     * redigiu e quem aprovou não pode depender de a pessoa ainda trabalhar cá.
     *
     * <p>Duas recusas propositadas, ambas sobre o mesmo risco de o laboratório
     * ficar sem quem administre: ninguém se desativa a si próprio, e o último
     * administrador ativo não pode ser desativado.
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Activate or deactivate a user (Admin only)")
    public ResponseEntity<?> setStatus(
            @PathVariable UUID id,
            @RequestBody StatusRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.labdms.infrastructure.security.UserPrincipal principal) {

        String target = request.status() == null ? "" : request.status().toUpperCase();
        if (!target.equals("ACTIVE") && !target.equals("INACTIVE")) {
            return ResponseEntity.badRequest().body(new MessageDto(
                    "Estado inválido. Use ACTIVE ou INACTIVE."));
        }

        UserJpaEntity user = userRepository.findById(id).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        if (target.equals("INACTIVE")) {
            if (id.equals(principal.getId())) {
                return ResponseEntity.badRequest().body(new MessageDto(
                        "Você não pode desativar a sua própria conta."));
            }
            if (isLastActiveAdmin(id)) {
                return ResponseEntity.badRequest().body(new MessageDto(
                        "Esta é a última conta de administrador ativa. Promova outra pessoa "
                        + "a administrador antes de desativar esta, ou o sistema fica sem quem o administre."));
            }
        }

        user.setStatus(target);
        userRepository.save(user);
        return ResponseEntity.ok(new MessageDto(target.equals("ACTIVE")
                ? "Conta reativada." : "Conta desativada."));
    }

    /**
     * Apaga a conta — só quando não há nada a perder.
     *
     * <p>Uma conta que já escreveu, aprovou ou sequer tocou no sistema deixou
     * rasto, e apagá-la transformaria esse rasto em "autor desconhecido". Por
     * isso a remoção só é permitida a contas sem histórico nenhum; para as
     * outras, a resposta explica que o caminho é desativar.
     *
     * <p>A verificação é feita por contagem em cada tabela que aponta para o
     * utilizador, e não confiando na chave estrangeira: uma FK sem ON DELETE
     * avisaria com um erro de base de dados que ninguém consegue interpretar.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a user with no history (Admin only)")
    public ResponseEntity<?> deleteUser(
            @PathVariable UUID id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.labdms.infrastructure.security.UserPrincipal principal) {

        if (userRepository.findById(id).isEmpty()) return ResponseEntity.notFound().build();

        if (id.equals(principal.getId())) {
            return ResponseEntity.badRequest().body(new MessageDto(
                    "Você não pode apagar a sua própria conta."));
        }
        if (isLastActiveAdmin(id)) {
            return ResponseEntity.badRequest().body(new MessageDto(
                    "Esta é a última conta de administrador ativa e não pode ser apagada."));
        }

        long history = countHistory(id);
        if (history > 0) {
            return ResponseEntity.badRequest().body(new MessageDto(
                    "Esta conta tem " + history + " registo(s) no sistema (documentos, versões, "
                    + "revisões ou auditoria) e apagá-la deixaria esse histórico sem responsável. "
                    + "Desative-a: o acesso é retirado e o histórico fica intacto."));
        }

        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", id);
        jdbcTemplate.update("DELETE FROM notifications WHERE user_id = ?", id);
        userRepository.deleteById(id);
        return ResponseEntity.ok(new MessageDto("Conta apagada."));
    }

    /** Quantos registos ficariam órfãos se esta conta desaparecesse. */
    private long countHistory(UUID userId) {
        long total = 0;
        // Cada par é tabela + coluna que aponta para o utilizador. Uma tabela que
        // ainda não exista nesta instalação é ignorada em vez de rebentar.
        String[][] refs = {
                {"documents", "created_by"}, {"commits", "author_id"},
                {"reviews", "reviewer_id"}, {"audit_log", "user_id"},
                {"document_access", "user_id"}, {"document_acknowledgements", "user_id"},
                {"document_group_members", "user_id"},
        };
        for (String[] ref : refs) {
            try {
                Integer n = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + ref[0] + " WHERE " + ref[1] + " = ?",
                        Integer.class, userId);
                if (n != null) total += n;
            } catch (org.springframework.dao.DataAccessException ignored) {
                // Tabela ou coluna ausente nesta versão do esquema.
            }
        }
        return total;
    }

    private boolean isLastActiveAdmin(UUID userId) {
        Integer othersActive = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM users u
                  JOIN user_roles ur ON ur.user_id = u.id
                  JOIN roles r ON r.id = ur.role_id
                 WHERE r.name = 'ROLE_ADMIN' AND u.status = 'ACTIVE' AND u.id <> ?
                """, Integer.class, userId);
        Integer isAdmin = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_roles ur
                  JOIN roles r ON r.id = ur.role_id
                 WHERE r.name = 'ROLE_ADMIN' AND ur.user_id = ?
                """, Integer.class, userId);
        return isAdmin != null && isAdmin > 0 && (othersActive == null || othersActive == 0);
    }

    /* ----------------------------- idioma ----------------------------- */

    /**
     * Guarda o idioma da própria conta.
     *
     * <p>Sem {@code @PreAuthorize} de administrador de propósito: isto é a
     * preferência de quem está autenticado, sobre a própria conta, e o
     * identificador vem do token — não de um parâmetro que se possa trocar.
     */
    @PutMapping("/me/language")
    @Operation(summary = "Save the signed-in user's preferred language")
    public ResponseEntity<?> setMyLanguage(
            @RequestBody LanguageRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.labdms.infrastructure.security.UserPrincipal principal) {

        String lang = request.language() == null ? "" : request.language().toLowerCase();
        if (!List.of("pt", "en", "fr", "de", "zh", "es").contains(lang)) {
            return ResponseEntity.badRequest().body(new MessageDto("Idioma não suportado: " + lang));
        }
        jdbcTemplate.update("UPDATE users SET preferred_language = ? WHERE id = ?",
                lang, principal.getId());
        return ResponseEntity.ok(new MessageDto(lang));
    }

    @GetMapping("/me/language")
    @Operation(summary = "Read the signed-in user's preferred language")
    public ResponseEntity<?> getMyLanguage(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.labdms.infrastructure.security.UserPrincipal principal) {
        List<String> found = jdbcTemplate.query(
                "SELECT preferred_language FROM users WHERE id = ?",
                (rs, n) -> rs.getString(1), principal.getId());
        return ResponseEntity.ok(new MessageDto(found.isEmpty() ? null : found.get(0)));
    }

    public record StatusRequest(String status) {}
    public record LanguageRequest(String language) {}
    /** @param authProvider LOCAL or KEYCLOAK — tells the screen whether a password exists here. */
    public record UserDto(UUID id, String email, String fullName, String status, String role, String authProvider) {
        public UserDto(UUID id, String email, String fullName, String status, String role) {
            this(id, email, fullName, status, role, "LOCAL");
        }
    }
    public record ApproveRequest(String roleName) {}
    public record UpdateRolesRequest(List<String> roleNames) {}
    public record RoleDto(UUID id, String name, String description) {}
    public record CreateUserRequest(String fullName, String email, String password, String roleName) {}
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}
    public record MessageDto(String message) {}
}
