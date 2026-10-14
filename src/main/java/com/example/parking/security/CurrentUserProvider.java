package com.example.parking.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Acces au compte connecte, utilise pour tracer l'operateur des entrees / sorties. */
@Component
public class CurrentUserProvider {

    public String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "systeme";
        }
        return authentication.getName();
    }

    public Long userId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SecurityUser securityUser) {
            return securityUser.getId();
        }
        return null;
    }

    public boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role));
    }

    /**
     * Identifiant du compte connecte s'il a le role USER (il ne doit voir que
     * ses propres donnees), {@code null} pour les roles de gestion.
     */
    public Long restrictedUserId() {
        return hasRole("USER") ? userId() : null;
    }

    /** Refuse a un compte USER l'acces a une ressource appartenant a un autre utilisateur. */
    public void checkOwnership(Long ownerId) {
        Long self = restrictedUserId();
        if (self != null && !self.equals(ownerId)) {
            throw new AccessDeniedException("Cette ressource appartient a un autre utilisateur");
        }
    }
}
