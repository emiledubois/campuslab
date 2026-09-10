package cl.campuslab.bff.mqadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Mirrors CatalogFacadeControllerTest/BookingsFacadeControllerTest (design doc §6) - a
 * real JDK HttpServer stands in for ms-campuslab-mq-admin. The ADMIN-only gate itself is
 * the pre-existing {@code /api/admin/**} path rule in SecurityConfig (verified here, not
 * re-implemented), covering this new route family by prefix with no new rule needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class MqAdminFacadeControllerTest {

    private static HttpServer fakeMqAdmin;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void mqAdminServiceUrl(DynamicPropertyRegistry registry) throws IOException {
        fakeMqAdmin = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeMqAdmin.start();
        registry.add("mq-admin.service-url", () -> "http://localhost:" + fakeMqAdmin.getAddress().getPort());
    }

    @AfterEach
    void resetHandlers() {
        try {
            fakeMqAdmin.removeContext("/api/admin/mq/queues");
        } catch (IllegalArgumentException noContextRegistered) {
            // not every test registers this context
        }
        try {
            fakeMqAdmin.removeContext("/api/admin/mq/dlq/q.cmd.email.dlq/requeue");
        } catch (IllegalArgumentException noContextRegistered) {
            // not every test registers this context
        }
    }

    @BeforeEach
    void stubTokens() {
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", List.of("ADMIN")));
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));
    }

    @Test
    void getQueues_forwardsToMqAdminAndReturnsItsBodyAndStatusUnchanged() throws Exception {
        fakeMqAdmin.createContext("/api/admin/mq/queues", exchange -> {
            byte[] body = "[{\"name\":\"q.cmd.email\"}]".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockMvc.perform(get("/api/admin/mq/queues").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("q.cmd.email"));
    }

    @Test
    void getQueues_forwardsCallersOriginalBearerTokenUnchanged() throws Exception {
        AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
        fakeMqAdmin.createContext("/api/admin/mq/queues", exchange -> {
            receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });

        mockMvc.perform(get("/api/admin/mq/queues").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());

        assertThat(receivedAuthHeader.get()).isEqualTo("Bearer admin-token");
    }

    @Test
    void getQueues_withTecnicoRole_returns403BeforeReachingMqAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/mq/queues").header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getQueues_withNoToken_returns401BeforeAnyForwarding() throws Exception {
        mockMvc.perform(get("/api/admin/mq/queues")).andExpect(status().isUnauthorized());
    }

    @Test
    void postRequeue_forwardsBodyAndPathVariableToMqAdmin() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedPath = new AtomicReference<>();
        fakeMqAdmin.createContext("/api/admin/mq/dlq/q.cmd.email.dlq/requeue", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] responseBody = "{\"dlqName\":\"q.cmd.email.dlq\",\"actualRequeuedCount\":2}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        String requestBody = "{\"count\":2}";

        mockMvc.perform(post("/api/admin/mq/dlq/q.cmd.email.dlq/requeue")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualRequeuedCount").value(2));
        assertThat(receivedPath.get()).isEqualTo("/api/admin/mq/dlq/q.cmd.email.dlq/requeue");
        assertThat(receivedBody.get()).isEqualTo(requestBody);
    }

    @Test
    void postRequeue_withTecnicoRole_returns403BeforeReachingMqAdmin() throws Exception {
        mockMvc.perform(post("/api/admin/mq/dlq/q.cmd.email.dlq/requeue")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getQueues_whenMqAdminIsUnreachable_returns503ProblemDetailNotAStackTrace() throws Exception {
        int port = fakeMqAdmin.getAddress().getPort();
        fakeMqAdmin.stop(0);
        try {
            mockMvc.perform(get("/api/admin/mq/queues").header("Authorization", "Bearer admin-token"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value("The mq-admin service is currently unavailable."))
                    .andExpect(jsonPath("$.stackTrace").doesNotExist());
        } finally {
            fakeMqAdmin = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            fakeMqAdmin.start();
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
