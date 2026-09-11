package cl.campuslab.kafkaadmin.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.campuslab.kafkaadmin.AbstractIntegrationTest;
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
 * Exercises kafka-admin's own resource-server filter chain independently of the BFF
 * (design doc §7 A07's defense-in-depth decision, AC3/AC13) - a mocked JwtDecoder
 * stands in for real issuer/signature/JWKS validation, mirroring mq-admin's own
 * SecurityIntegrationTest; the real Entra validator chain is proven separately, against
 * a real (non-mocked) JwtDecoder, by EntraJwtValidationTest. kafka-admin has no public
 * port for an end-to-end HTTP probe of this independent validation (design doc AC13's
 * own accepted coverage-gap note, matching mq-admin's precedent).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void getTopics_withNoAuthorizationHeader_returns401WithNoClaimsOrStackTrace() throws Exception {
        mockMvc.perform(get("/api/admin/kafka/topics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.sub").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void getTopics_withMalformedToken_returns401() throws Exception {
        given(jwtDecoder.decode(anyString())).willThrow(new BadJwtException("Malformed token"));

        mockMvc.perform(get("/api/admin/kafka/topics").header("Authorization", "Bearer garbage-not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTopics_withAdminToken_returns200() throws Exception {
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", List.of("ADMIN")));

        mockMvc.perform(get("/api/admin/kafka/topics").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void getConsumerGroups_withAdminToken_returns200() throws Exception {
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", List.of("ADMIN")));

        mockMvc.perform(get("/api/admin/kafka/consumer-groups").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void getDlt_withAdminToken_returns200() throws Exception {
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", List.of("ADMIN")));

        mockMvc.perform(get("/api/admin/kafka/dlt").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void getTopics_withTecnicoToken_returns403() throws Exception {
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));

        mockMvc.perform(get("/api/admin/kafka/topics").header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getTopics_withEstudianteToken_returns403() throws Exception {
        given(jwtDecoder.decode("estudiante-token")).willReturn(jwt("estudiante-uuid", List.of("ESTUDIANTE")));

        mockMvc.perform(get("/api/admin/kafka/topics").header("Authorization", "Bearer estudiante-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getTopics_withAuditorToken_returns403() throws Exception {
        given(jwtDecoder.decode("auditor-token")).willReturn(jwt("auditor-uuid", List.of("AUDITOR")));

        mockMvc.perform(get("/api/admin/kafka/topics").header("Authorization", "Bearer auditor-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getConsumerGroups_withTecnicoToken_returns403() throws Exception {
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));

        mockMvc.perform(get("/api/admin/kafka/consumer-groups").header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getDlt_withTecnicoToken_returns403() throws Exception {
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));

        mockMvc.perform(get("/api/admin/kafka/dlt").header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getActuatorHealth_withNoToken_returns200Up() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    private static Jwt jwt(String subject, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("oid", subject + "-oid")
                .claim("iss", "https://login.microsoftonline.com/test-tenant/v2.0")
                .claim("roles", roles)
                .build();
    }
}
