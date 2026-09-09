package cl.campuslab.bff.web;

import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Shared helper so both /api/me and /api/admin/ping build their response strictly
 * from the already-validated Jwt/authorities exposed by Spring Security (OWASP A08) -
 * neither controller re-parses or trusts the raw token string directly.
 */
final class AuthenticatedPrincipal {

    private AuthenticatedPrincipal() {
    }

    static List<String> roles(JwtAuthenticationToken authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority)
                .toList();
    }
}
