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
        String sql = "SELECT u.id, u.email, u.full_name, u.status, " +
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
            rs.getString("role_name")
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

    public record UserDto(UUID id, String email, String fullName, String status, String role) {}
    public record ApproveRequest(String roleName) {}
    public record UpdateRolesRequest(List<String> roleNames) {}
    public record RoleDto(UUID id, String name, String description) {}
    public record CreateUserRequest(String fullName, String email, String password, String roleName) {}
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}
    public record MessageDto(String message) {}
}
