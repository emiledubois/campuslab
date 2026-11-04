package cl.campuslab.bff.kafkaadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
        for (String path : ALL_REGISTERABLE_CONTEXT_PATHS) {
            try {
                fakeKafkaAdmin.removeContext(path);
            } catch (IllegalArgumentException noContextRegistered) {
                // not every test registers this context
            }
        }
    }

    private static final List<String> ALL_REGISTERABLE_CONTEXT_PATHS = List.of(
            "/api/admin/kafka",
            "/api/admin/kafka/topics",
            "/api/admin/kafka/consumer-groups",
            "/api/admin/kafka/dlt",
            "/api/admin/kafka/topics/demo.ops.topic",
            "/api/admin/kafka/topics/demo.ops.topic/config",
            "/api/admin/kafka/topics/demo.ops.topic/partitions",
            "/api/admin/kafka/consumer-groups/demo.ops.group",
            "/api/admin/kafka/consumer-groups/demo.ops.group/reset-offsets",
            "/api/admin/kafka/topics/");

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

    // --- kafka-admin-write.md §7/AC35-AC38: 6 rutas nuevas con escritura ---

    private static final String CREATE_BODY = "{\"name\":\"demo.ops.topic\",\"partitions\":3}";
    private static final String CONFIG_BODY = "{\"configs\":{\"retention.ms\":\"172800000\"}}";
    private static final String PARTITIONS_BODY = "{\"totalCount\":6}";
    private static final String RESET_BODY = "{\"topic\":\"demo.ops.topic\",\"strategy\":\"EARLIEST\"}";

    static Stream<Arguments> rutasNuevas() {
        return Stream.of(
                Arguments.of("POST", "/api/admin/kafka/topics", CREATE_BODY, 201,
                        "{\"name\":\"demo.ops.topic\",\"partitions\":3,\"replicationFactor\":1,\"configs\":{}}"),
                Arguments.of("DELETE", "/api/admin/kafka/topics/demo.ops.topic", null, 204, null),
                Arguments.of("PATCH", "/api/admin/kafka/topics/demo.ops.topic/config", CONFIG_BODY, 200,
                        "{\"name\":\"demo.ops.topic\",\"configs\":{\"retention.ms\":\"172800000\"}}"),
                Arguments.of("POST", "/api/admin/kafka/topics/demo.ops.topic/partitions", PARTITIONS_BODY, 200,
                        "{\"name\":\"demo.ops.topic\",\"previousPartitions\":3,\"partitions\":6}"),
                Arguments.of("DELETE", "/api/admin/kafka/consumer-groups/demo.ops.group", null, 204, null),
                Arguments.of("POST", "/api/admin/kafka/consumer-groups/demo.ops.group/reset-offsets", RESET_BODY, 200,
                        "{\"groupId\":\"demo.ops.group\",\"strategy\":\"EARLIEST\",\"partitions\":[]}"));
    }

    @ParameterizedTest
    @MethodSource("rutasNuevas")
    void rutasNuevas_conAdmin_reenvianMetodoPathYCuerpoExactosYDevuelvenLaRespuestaSinCambios(
            String method, String path, String requestBody, int status, String responseBody) throws Exception {
        // Arrange
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<String> receivedPath = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        AtomicReference<String> receivedContentType = new AtomicReference<>();
        fakeKafkaAdmin.createContext(path, exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedPath.set(exchange.getRequestURI().getRawPath());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            if (responseBody == null) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });

        // Act
        var result = mockMvc.perform(request(method, path, requestBody).header("Authorization", "Bearer admin-token"))
                .andExpect(status().is(status));

        // Assert
        if (responseBody != null) {
            result.andExpect(content().json(responseBody, true));
        }
        assertThat(receivedMethod.get()).isEqualTo(method);
        assertThat(receivedPath.get()).isEqualTo(path);
        assertThat(receivedBody.get()).isEqualTo(requestBody == null ? "" : requestBody);
        assertThat(receivedAuth.get()).isEqualTo("Bearer admin-token");
        if (requestBody != null) {
            assertThat(receivedContentType.get()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        }
    }

    static Stream<Arguments> erroresDeKafkaAdmin() {
        return Stream.of(
                Arguments.of(409, "DELETE", "/api/admin/kafka/topics/demo.ops.topic", null,
                        "Operacion 'delete' rechazada sobre topico 'bookings.events'"),
                Arguments.of(404, "DELETE", "/api/admin/kafka/consumer-groups/demo.ops.group", null,
                        "No existe un consumer group llamado 'demo.ops.group'."),
                Arguments.of(400, "POST", "/api/admin/kafka/topics/demo.ops.topic/partitions", PARTITIONS_BODY,
                        "La nueva cantidad de particiones (6) debe ser mayor que la actual (6)"));
    }

    @ParameterizedTest
    @MethodSource("erroresDeKafkaAdmin")
    void rutasNuevas_errorProblemJsonDeKafkaAdmin_llegaIdenticoAlCliente(
            int status, String method, String path, String requestBody, String detail) throws Exception {
        // Arrange
        String problem = "{\"type\":\"about:blank\",\"title\":\"T\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\",\"instance\":\"" + path + "\"}";
        fakeKafkaAdmin.createContext(path, exchange -> {
            byte[] bytes = problem.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        // Act + Assert
        mockMvc.perform(request(method, path, requestBody).header("Authorization", "Bearer admin-token"))
                .andExpect(status().is(status))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(content().json(problem, true));
    }

    @ParameterizedTest
    @MethodSource("rutasNuevas")
    void rutasNuevas_conTecnico_retornan403SinReenviar(
            String method, String path, String requestBody, int ignoredStatus, String ignoredBody) throws Exception {
        // Arrange
        AtomicBoolean reached = new AtomicBoolean(false);
        fakeKafkaAdmin.createContext("/api/admin/kafka", exchange -> {
            reached.set(true);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        // Act + Assert
        mockMvc.perform(request(method, path, requestBody).header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isForbidden());
        assertThat(reached.get()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("rutasNuevas")
    void rutasNuevas_sinToken_retornan401SinReenviar(
            String method, String path, String requestBody, int ignoredStatus, String ignoredBody) throws Exception {
        // Arrange
        AtomicBoolean reached = new AtomicBoolean(false);
        fakeKafkaAdmin.createContext("/api/admin/kafka", exchange -> {
            reached.set(true);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        // Act + Assert
        mockMvc.perform(request(method, path, requestBody)).andExpect(status().isUnauthorized());
        assertThat(reached.get()).isFalse();
    }

    @Test
    void deleteTopic_conKafkaAdminCaido_retorna503ProblemDetail() throws Exception {
        // Arrange
        int port = fakeKafkaAdmin.getAddress().getPort();
        fakeKafkaAdmin.stop(0);
        try {
            // Act + Assert
            mockMvc.perform(delete("/api/admin/kafka/topics/x").header("Authorization", "Bearer admin-token"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value("The kafka-admin service is currently unavailable."))
                    .andExpect(jsonPath("$.stackTrace").doesNotExist());
        } finally {
            fakeKafkaAdmin = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            fakeKafkaAdmin.start();
        }
    }

    @Test
    void deleteTopic_conLlavesEnElNombre_llegaCodificadoYNoRetorna500() throws Exception {
        // Arrange
        AtomicReference<String> receivedRawPath = new AtomicReference<>();
        fakeKafkaAdmin.createContext("/api/admin/kafka/topics/", exchange -> {
            receivedRawPath.set(exchange.getRequestURI().getRawPath());
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        // Act + Assert
        mockMvc.perform(delete(URI.create("/api/admin/kafka/topics/a%7Bb%7D")).header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNotFound());
        assertThat(receivedRawPath.get()).isEqualTo("/api/admin/kafka/topics/a%7Bb%7D");
    }

    private static MockHttpServletRequestBuilder request(String method, String path, String body) {
        MockHttpServletRequestBuilder builder = switch (method) {
            case "POST" -> post(path);
            case "PATCH" -> patch(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException(method);
        };
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return builder;
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
