package cl.campuslab.bff.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Mirrors CatalogFacadeControllerTest/MqAdminFacadeControllerTest (design doc §6) - a
 * real JDK HttpServer stands in for ms-campuslab-audit. The ADMIN/AUDITOR-only gate
 * itself is the new {@code /api/audit/**} path rule in SecurityConfig (verified here,
 * not re-implemented).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AuditFacadeControllerTest {

    private static HttpServer fakeAudit;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void auditServiceUrl(DynamicPropertyRegistry registry) throws IOException {
        fakeAudit = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeAudit.start();
        registry.add("audit.service-url", () -> "http://localhost:" + fakeAudit.getAddress().getPort());
    }

    @AfterEach
    void resetHandlers() {
        try {
            fakeAudit.removeContext("/api/audit/timeline");
        } catch (IllegalArgumentException noContextRegistered) {
            // not every test registers this context
        }
    }

    @BeforeEach
    void stubTokens() {
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", List.of("ADMIN")));
        given(jwtDecoder.decode("auditor-token")).willReturn(jwt("auditor-uuid", List.of("AUDITOR")));
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));
        given(jwtDecoder.decode("estudiante-token")).willReturn(jwt("estudiante-uuid", List.of("ESTUDIANTE")));
    }

    @Test
    void getTimeline_forwardsToAuditAndReturnsItsBodyAndStatusUnchanged() throws Exception {
        fakeAudit.createContext("/api/audit/timeline", exchange -> {
            byte[] body = "[{\"eventType\":\"BOOKING_APROBADA\"}]".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("BOOKING_APROBADA"));
    }

    @Test
    void getTimeline_forwardsQueryStringAndCallersBearerToken() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
        fakeAudit.createContext("/api/audit/timeline", exchange -> {
            receivedPath.set(exchange.getRequestURI().toString());
            receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });

        mockMvc.perform(get("/api/audit/timeline?eventType=BOOKING_APROBADA").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());

        assertThat(receivedPath.get()).isEqualTo("/api/audit/timeline?eventType=BOOKING_APROBADA");
        assertThat(receivedAuthHeader.get()).isEqualTo("Bearer admin-token");
    }

    @Test
    void getTimeline_asAuditor_alsoReaches200() throws Exception {
        fakeAudit.createContext("/api/audit/timeline", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });

        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer auditor-token"))
                .andExpect(status().isOk());
    }

    @Test
    void getTimeline_withTecnicoRole_returns403BeforeReachingAudit() throws Exception {
        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getTimeline_withEstudianteRole_returns403BeforeReachingAudit() throws Exception {
        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer estudiante-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getTimeline_withNoToken_returns401BeforeAnyForwarding() throws Exception {
        mockMvc.perform(get("/api/audit/timeline")).andExpect(status().isUnauthorized());
    }

    @Test
    void getTimeline_whenAuditIsUnreachable_returns503ProblemDetailNotAStackTrace() throws Exception {
        int port = fakeAudit.getAddress().getPort();
        fakeAudit.stop(0);
        try {
            mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer admin-token"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value("The audit service is currently unavailable."))
                    .andExpect(jsonPath("$.stackTrace").doesNotExist());
        } finally {
            fakeAudit = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            fakeAudit.start();
        }
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
