package com.labdms.infrastructure.security;

import com.labdms.domain.model.user.User;
import com.labdms.domain.model.user.UserStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class UserPrincipal implements UserDetails {

    private final UUID id;
    private final String username;
    private final String email;
    private final String fullName;
    private final String password;
    private final UserStatus status;
    private final Collection<? extends GrantedAuthority> authorities;

    public UserPrincipal(UUID id, String username, String email, String fullName, String password,
                         UserStatus status, Collection<? extends GrantedAuthority> authorities) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.fullName = fullName;
        this.password = password;
        this.status = status == null ? UserStatus.ACTIVE : status;
        this.authorities = authorities;
    }

    public static UserPrincipal create(User user, List<String> permissions) {
        List<GrantedAuthority> authorities = permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        return new UserPrincipal(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getPasswordHash(),
                user.getStatus(),
                authorities
        );
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public UserStatus getStatus() {
        return status;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * A locked account cannot sign in.
     *
     * <p>These two answers used to be hardcoded {@code true}, so an account that
     * was PENDING approval, deactivated by an administrator or LOCKED still
     * signed in and read every internal document. The status column existed
     * and was checked on writes only; reading was open to anyone with a
     * password. Spring's authentication provider asks here, so the answer has
     * to be the real one.
     */
    @Override
    public boolean isAccountNonLocked() {
        return status != UserStatus.LOCKED;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /** Only an ACTIVE account is enabled: pending and deactivated ones are not. */
    @Override
    public boolean isEnabled() {
        return status == UserStatus.ACTIVE;
    }
}
