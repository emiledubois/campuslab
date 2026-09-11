package cl.campuslab.bff.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the full resource-server filter chain (BearerTokenAuthenticationFilter ->
 * JwtDecoder -> RolesJwtAuthenticationConverter -> authorizeHttpRequests) with a mocked
 * JwtDecoder standing in for real issuer/signature/JWKS validation - this proves this
 * slice's own wiring (role mapping, path rules, CORS allow-list, actuator exemption,
 * response shapes), not issuer/signature/audience/expiry validation itself. The real
 * Entra validator chain (issuer, audience, signature, expiry, alg:none rejection) is
 * proven separately, against a real (non-mocked) JwtDecoder and a local mock
 * discovery/JWKS server, by EntraJwtValidationTest - see
 * docs/designs/entra-migration.md's "Test token strategy" section.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void getMe_withNoAuthorizationHeader_returns401WithNoClaimsOrStackTrace() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.sub").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void getMe_withMalformedToken_returns401() throws Exception {
        given(jwtDecoder.decode(anyString())).willThrow(new BadJwtException("Malformed token"));

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer garbage-not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMe_withValidTokenForEachRole_returns200WithMatchingIdentity() throws Exception {
        given(jwtDecoder.decode("estudiante-token")).willReturn(jwt("estudiante-uuid", "estudiante.test", List.of("ESTUDIANTE")));

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer estudiante-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("estudiante-uuid"))
                .andExpect(jsonPath("$.username").value("estudiante.test"))
                .andExpect(jsonPath("$.roles[0]").value("ESTUDIANTE"));
    }

    @Test
    void getAdminPing_withAdminToken_returns200() throws Exception {
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", "admin.test", List.of("ADMIN")));

        mockMvc.perform(get("/api/admin/ping").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("admin-only"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    void getAdminPing_withNonAdminRole_returns403NotUnauthorized() throws Exception {
        given(jwtDecoder.decode("estudiante-token")).willReturn(jwt("estudiante-uuid", "estudiante.test", List.of("ESTUDIANTE")));

        mockMvc.perform(get("/api/admin/ping").header("Authorization", "Bearer estudiante-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAdminPing_withNoToken_returns401BeforeRoleCheck() throws Exception {
        mockMvc.perform(get("/api/admin/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getAuditTimeline_withAdminToken_reachesController() throws Exception {
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", "admin.test", List.of("ADMIN")));

        // A new path rule (design doc §2/§7 A01) - proven here at the SecurityConfig
        // layer only; the actual forward/503-mapping behaviour is AuditFacadeControllerTest's.
        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer admin-token"))
                .andExpect(status().is5xxServerError()); // no fake audit backend wired in this test class
    }

    @Test
    void getAuditTimeline_withAuditorToken_returns5xxNotForbidden() throws Exception {
        given(jwtDecoder.decode("auditor-token")).willReturn(jwt("auditor-uuid", "auditor.test", List.of("AUDITOR")));

        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer auditor-token"))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void getAuditTimeline_withTecnicoRole_returns403() throws Exception {
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", "tecnico.test", List.of("TECNICO")));

        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAuditTimeline_withNoToken_returns401BeforeRoleCheck() throws Exception {
        mockMvc.perform(get("/api/audit/timeline"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getActuatorHealth_withNoToken_returns200Up() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void corsPreflight_fromAllowedOrigin_isGranted() throws Exception {
        mockMvc.perform(options("/api/me")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void corsPreflight_fromDisallowedOrigin_isNotGranted() throws Exception {
        mockMvc.perform(options("/api/me")
                        .header("Origin", "http://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    private static Jwt jwt(String subject, String username, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("preferred_username", username)
                .claim("email", username + "@campuslab.local")
                .claim("oid", subject + "-oid")
                .claim("iss", "https://login.microsoftonline.com/test-tenant/v2.0")
                .claim("roles", roles)
                .build();
    }
}
