package ar.changeo.security;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record AccountPrincipal(UUID id, String contact, String passwordHash, long epoch) implements UserDetails, Serializable {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT")); }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return contact; }
}
