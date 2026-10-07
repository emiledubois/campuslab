package cl.campuslab.mqadmin.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.campuslab.mqadmin.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises mq-admin's own resource-server filter chain independently of the BFF
 * (design doc §2.3's defense-in-depth decision, AC4/AC16) - a mocked JwtDecoder stands
 * in for real issuer/signature/JWKS validation, mirroring catalog's/bookings' own
 * SecurityIntegrationTest; the real Entra validator chain is proven separately, against
 * a real (non-mocked) JwtDecoder, by EntraJwtValidationTest. mq-admin has no public port
 * for an end-to-end HTTP probe of this independent validation (design doc AC16's own
 * accepted coverage-gap note, matching catalog's/bookings' precedent).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void getQueues_withNoAuthorizationHeader_returns401WithNoClaimsOrStackTrace() throws Exception {
        mockMvc.perform(get("/api/admin/mq/queues"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.sub").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void getQueues_withMalformedToken_returns401() throws Exception {
        given(jwtDecoder.decode(anyString())).willThrow(new BadJwtException("Malformed token"));

        mockMvc.perform(get("/api/admin/mq/queues").header("Authorization", "Bearer garbage-not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getQueues_withAdminToken_returns200() throws Exception {
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", List.of("ADMIN")));

        mockMvc.perform(get("/api/admin/mq/queues").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void getQueues_withTecnicoToken_returns403() throws Exception {
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));

        mockMvc.perform(get("/api/admin/mq/queues").header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getQueues_withEstudianteToken_returns403() throws Exception {
        given(jwtDecoder.decode("estudiante-token")).willReturn(jwt("estudiante-uuid", List.of("ESTUDIANTE")));

        mockMvc.perform(get("/api/admin/mq/queues").header("Authorization", "Bearer estudiante-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getQueues_withAuditorToken_returns403() throws Exception {
        given(jwtDecoder.decode("auditor-token")).willReturn(jwt("auditor-uuid", List.of("AUDITOR")));

        mockMvc.perform(get("/api/admin/mq/queues").header("Authorization", "Bearer auditor-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postRequeue_withTecnicoToken_returns403() throws Exception {
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));

        mockMvc.perform(post("/api/admin/mq/dlq/q.cmd.email.dlq/requeue")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postRequeue_withNoToken_returns401BeforeRoleCheck() throws Exception {
        mockMvc.perform(post("/api/admin/mq/dlq/q.cmd.email.dlq/requeue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\":true}"))
                .andExpect(status().isUnauthorized());
    }

    // --- Slice A: ADMIN-only on all 7 new endpoints (design doc AC16, 21 checks) ---

    private static final String QUEUE_BODY = "{\"name\":\"q.sec.probe\"}";
    private static final String EXCHANGE_BODY = "{\"name\":\"sec.probe.exchange\",\"type\":\"direct\"}";
    private static final String BINDING_BODY =
            "{\"source\":\"sec.probe.exchange\",\"destination\":\"q.sec.probe\",\"destinationType\":\"QUEUE\",\"routingKey\":\"x\"}";

    static Stream<MockHttpServletRequestBuilder> newEndpointRequests() {
        return Stream.of(
                MockMvcRequestBuilders.post("/api/admin/mq/queues").contentType(MediaType.APPLICATION_JSON).content(QUEUE_BODY),
                delete("/api/admin/mq/queues/q.sec.probe"),
                MockMvcRequestBuilders.post("/api/admin/mq/queues/q.sec.probe/purge"),
                MockMvcRequestBuilders.post("/api/admin/mq/exchanges").contentType(MediaType.APPLICATION_JSON).content(EXCHANGE_BODY),
                delete("/api/admin/mq/exchanges/sec.probe.exchange"),
                MockMvcRequestBuilders.post("/api/admin/mq/bindings").contentType(MediaType.APPLICATION_JSON).content(BINDING_BODY),
                delete("/api/admin/mq/bindings").contentType(MediaType.APPLICATION_JSON).content(BINDING_BODY));
    }

    @ParameterizedTest
    @MethodSource("newEndpointRequests")
    void newEndpoints_withTecnicoToken_return403(MockHttpServletRequestBuilder request) throws Exception {
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));

        mockMvc.perform(request.with(req -> {
                    req.addHeader("Authorization", "Bearer tecnico-token");
                    return req;
                }))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("newEndpointRequests")
    void newEndpoints_withEstudianteToken_return403(MockHttpServletRequestBuilder request) throws Exception {
        given(jwtDecoder.decode("estudiante-token")).willReturn(jwt("estudiante-uuid", List.of("ESTUDIANTE")));

        mockMvc.perform(request.with(req -> {
                    req.addHeader("Authorization", "Bearer estudiante-token");
                    return req;
                }))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("newEndpointRequests")
    void newEndpoints_withAuditorToken_return403(MockHttpServletRequestBuilder request) throws Exception {
        given(jwtDecoder.decode("auditor-token")).willReturn(jwt("auditor-uuid", List.of("AUDITOR")));

        mockMvc.perform(request.with(req -> {
                    req.addHeader("Authorization", "Bearer auditor-token");
                    return req;
                }))
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
