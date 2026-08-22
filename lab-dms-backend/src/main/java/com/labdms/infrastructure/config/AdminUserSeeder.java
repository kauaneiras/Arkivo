package com.labdms.infrastructure.config;

import com.labdms.infrastructure.adapter.out.persistence.entity.RoleJpaEntity;
import com.labdms.infrastructure.adapter.out.persistence.entity.UserJpaEntity;
import com.labdms.infrastructure.adapter.out.persistence.repository.RoleJpaRepository;
import com.labdms.infrastructure.adapter.out.persistence.repository.UserJpaRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

@Configuration
public class AdminUserSeeder {

    @Bean
    public CommandLineRunner initAdmin(UserJpaRepository userRepository,
                                       RoleJpaRepository roleRepository,
                                       PasswordEncoder passwordEncoder,
                                       JdbcTemplate jdbcTemplate) {
        return args -> {
            // Check if admin role exists
            RoleJpaEntity adminRole = roleRepository.findByName("ROLE_ADMIN").orElseGet(() -> {
                RoleJpaEntity role = new RoleJpaEntity();
                role.setId(UUID.randomUUID());
                role.setName("ROLE_ADMIN");
                role.setCreatedAt(LocalDateTime.now());
                return roleRepository.save(role);
            });

            // Check if user exists
            if (userRepository.findByEmail("admin@email.com").isEmpty()) {
                UserJpaEntity adminUser = new UserJpaEntity();
                adminUser.setId(UUID.randomUUID());
                adminUser.setUsername("admin@email.com");
                adminUser.setEmail("admin@email.com");
                adminUser.setPasswordHash(passwordEncoder.encode("123456"));
                adminUser.setFullName("System Administrator");
                adminUser.setStatus("ACTIVE");
                adminUser.setCreatedAt(LocalDateTime.now());
                adminUser.setUpdatedAt(LocalDateTime.now());

                userRepository.save(adminUser);
                
                // Link user and role
                jdbcTemplate.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                        adminUser.getId(), adminRole.getId());
                
                System.out.println("Admin user created: admin@email.com / 123456");
            }
        };
    }
}
