package cl.campuslab.bff.report;

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
 * Mirrors AuditFacadeControllerTest (design doc §6) - a real JDK HttpServer stands in
 * for ms-campuslab-report. The ADMIN-only gate itself is the new {@code
 * /api/report/**} path rule in SecurityConfig (verified here, not re-implemented).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ReportFacadeControllerTest {

    private static HttpServer fakeReport;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void reportServiceUrl(DynamicPropertyRegistry registry) throws IOException {
        fakeReport = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeReport.start();
        registry.add("report.service-url", () -> "http://localhost:" + fakeReport.getAddress().getPort());
    }

    @AfterEach
    void resetHandlers() {
        for (String path : List.of("/api/report/kpis", "/api/report/top-resources")) {
            try {
                fakeReport.removeContext(path);
            } catch (IllegalArgumentException noContextRegistered) {
                // not every test registers this context
            }
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
    void getKpis_forwardsToReportAndReturnsItsBodyAndStatusUnchanged() throws Exception {
        fakeReport.createContext("/api/report/kpis", exchange -> {
            byte[] body = "{\"range\":\"last24h\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockMvc.perform(get("/api/report/kpis").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("last24h"));
    }

    @Test
    void getKpis_forwardsQueryStringAndCallersBearerToken() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
        fakeReport.createContext("/api/report/kpis", exchange -> {
            receivedPath.set(exchange.getRequestURI().toString());
            receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });

        mockMvc.perform(get("/api/report/kpis?range=last7d").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());

        assertThat(receivedPath.get()).isEqualTo("/api/report/kpis?range=last7d");
        assertThat(receivedAuthHeader.get()).isEqualTo("Bearer admin-token");
    }

    @Test
    void getTopResources_forwardsToReport() throws Exception {
        fakeReport.createContext("/api/report/top-resources", exchange -> {
            byte[] body = "{\"range\":\"last7d\",\"resources\":[]}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockMvc.perform(get("/api/report/top-resources").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("last7d"));
    }

    @Test
    void getKpis_withAuditorRole_returns403BeforeReachingReport() throws Exception {
        mockMvc.perform(get("/api/report/kpis").header("Authorization", "Bearer auditor-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getKpis_withTecnicoRole_returns403BeforeReachingReport() throws Exception {
        mockMvc.perform(get("/api/report/kpis").header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getKpis_withEstudianteRole_returns403BeforeReachingReport() throws Exception {
        mockMvc.perform(get("/api/report/kpis").header("Authorization", "Bearer estudiante-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getKpis_withNoToken_returns401BeforeAnyForwarding() throws Exception {
        mockMvc.perform(get("/api/report/kpis")).andExpect(status().isUnauthorized());
    }

    @Test
    void getKpis_whenReportIsUnreachable_returns503ProblemDetailNotAStackTrace() throws Exception {
        int port = fakeReport.getAddress().getPort();
        fakeReport.stop(0);
        try {
            mockMvc.perform(get("/api/report/kpis").header("Authorization", "Bearer admin-token"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value("The report service is currently unavailable."))
                    .andExpect(jsonPath("$.stackTrace").doesNotExist());
        } finally {
            fakeReport = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            fakeReport.start();
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
