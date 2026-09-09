package cl.campuslab.bff.web;

import cl.campuslab.bff.web.dto.AdminPingResponse;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately minimal diagnostic endpoint whose only purpose is to prove the role
 * mapping -> authorization pipeline end to end, since /api/me has no role restriction
 * and no real ADMIN-gated business endpoint exists yet (lands in slice 2, catalog writes).
 * ADMIN-only enforcement itself lives in the path rule in SecurityConfig, not here.
 */
@RestController
public class AdminPingController {

    private static final Logger log = LoggerFactory.getLogger(AdminPingController.class);

    @GetMapping("/api/admin/ping")
    public AdminPingResponse ping(JwtAuthenticationToken authentication) {
        List<String> roles = AuthenticatedPrincipal.roles(authentication);
        log.info("Admin ping accessed: sub=[{}] roles=[{}] path=[/api/admin/ping] timestamp=[{}]",
                authentication.getToken().getSubject(), roles, Instant.now());
        return new AdminPingResponse("admin-only", authentication.getToken().getSubject(), roles);
    }
}
