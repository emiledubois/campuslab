package cl.campuslab.bff.kafkaadmin;

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
 * Mirrors MqAdminFacadeControllerTest (design doc §6) - a real JDK HttpServer stands in
 * for ms-campuslab-kafka-admin. The ADMIN-only gate itself is the pre-existing {@code
 * /api/admin/**} path rule in SecurityConfig (verified here, not re-implemented),
 * covering this new route family by prefix with no new rule needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class KafkaAdminFacadeControllerTest {

    private static HttpServer fakeKafkaAdmin;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void kafkaAdminServiceUrl(DynamicPropertyRegistry registry) throws IOException {
        fakeKafkaAdmin = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeKafkaAdmin.start();
        registry.add("kafka-admin.service-url", () -> "http://localhost:" + fakeKafkaAdmin.getAddress().getPort());
    }

    @AfterEach
    void resetHandlers() {
        for (String path : List.of("/api/admin/kafka/topics", "/api/admin/kafka/consumer-groups", "/api/admin/kafka/dlt")) {
            try {
                fakeKafkaAdmin.removeContext(path);
            } catch (IllegalArgumentException noContextRegistered) {
                // not every test registers this context
            }
        }
    }

    @BeforeEach
    void stubTokens() {
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", List.of("ADMIN")));
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));
    }

    @Test
    void getTopics_forwardsToKafkaAdminAndReturnsItsBodyAndStatusUnchanged() throws Exception {
        fakeKafkaAdmin.createContext("/api/admin/kafka/topics", exchange -> {
            byte[] body = "[{\"name\":\"bookings.events\"}]".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockMvc.perform(get("/api/admin/kafka/topics").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("bookings.events"));
    }

    @Test
    void getConsumerGroups_forwardsCallersOriginalBearerTokenUnchanged() throws Exception {
        AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
        fakeKafkaAdmin.createContext("/api/admin/kafka/consumer-groups", exchange -> {
            receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });

        mockMvc.perform(get("/api/admin/kafka/consumer-groups").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());

        assertThat(receivedAuthHeader.get()).isEqualTo("Bearer admin-token");
    }

    @Test
    void getDlt_forwardsToKafkaAdmin() throws Exception {
        fakeKafkaAdmin.createContext("/api/admin/kafka/dlt", exchange -> {
            byte[] body = "[{\"name\":\"bookings.events.audit-service.DLT\",\"approximateMessageCount\":0}]".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockMvc.perform(get("/api/admin/kafka/dlt").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].approximateMessageCount").value(0));
    }

    @Test
    void getTopics_withTecnicoRole_returns403BeforeReachingKafkaAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/kafka/topics").header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getTopics_withNoToken_returns401BeforeAnyForwarding() throws Exception {
        mockMvc.perform(get("/api/admin/kafka/topics")).andExpect(status().isUnauthorized());
    }

    @Test
    void getTopics_whenKafkaAdminIsUnreachable_returns503ProblemDetailNotAStackTrace() throws Exception {
        int port = fakeKafkaAdmin.getAddress().getPort();
        fakeKafkaAdmin.stop(0);
        try {
            mockMvc.perform(get("/api/admin/kafka/topics").header("Authorization", "Bearer admin-token"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value("The kafka-admin service is currently unavailable."))
                    .andExpect(jsonPath("$.stackTrace").doesNotExist());
        } finally {
            fakeKafkaAdmin = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            fakeKafkaAdmin.start();
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
