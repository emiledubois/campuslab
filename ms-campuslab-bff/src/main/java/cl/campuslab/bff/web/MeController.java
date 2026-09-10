package cl.campuslab.bff.web;

import cl.campuslab.bff.web.dto.MeResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * No role restriction - every authenticated role needs to know who it is, and the
 * identity returned is always derived from the caller's own token, so there is no
 * ownership check to make here (there is no path/body id a caller could manipulate).
 * {@code oid} (Entra's tenant-wide object id) is what cross-service logging/display
 * keys on going forward, not the pairwise-per-application {@code sub} - both are
 * returned, but {@code oid} may defensively fall back to {@code sub} like the other
 * display fields since this is identity display, not the bookings ownership check
 * (which must never fall back, see BookingService).
 */
@RestController
public class MeController {

    @GetMapping("/api/me")
    public MeResponse me(JwtAuthenticationToken authentication) {
        Jwt jwt = authentication.getToken();
        String username = firstClaim(jwt, "preferred_username", "email", "name");
        String email = firstClaim(jwt, "email", "preferred_username", "name");
        String oid = firstClaim(jwt, "oid");
        return new MeResponse(
                jwt.getSubject(), oid, username, email, AuthenticatedPrincipal.roles(authentication), jwt.getIssuer().toString());
    }

    private static String firstClaim(Jwt jwt, String... claimNames) {
        for (String claimName : claimNames) {
            String value = jwt.getClaimAsString(claimName);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return jwt.getSubject();
    }
}
