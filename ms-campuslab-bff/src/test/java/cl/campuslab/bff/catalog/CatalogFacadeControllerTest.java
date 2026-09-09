package cl.campuslab.bff.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * The Facade forwards request/response bodies unparsed (OWASP A08) - a real JDK
 * HttpServer stands in for ms-campuslab-catalog so these tests exercise the actual
 * RestClient plumbing (headers, body bytes, status codes) rather than mocking the
 * fluent RestClient API itself, which would only prove the mock was configured right.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class CatalogFacadeControllerTest {

    private static HttpServer fakeCatalog;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void catalogServiceUrl(DynamicPropertyRegistry registry) throws IOException {
        fakeCatalog = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeCatalog.start();
        registry.add("catalog.service-url", () -> "http://localhost:" + fakeCatalog.getAddress().getPort());
    }

    @AfterEach
    void resetHandlers() {
        try {
            fakeCatalog.removeContext("/api/catalog/resources");
        } catch (IllegalArgumentException noContextRegistered) {
            // this test never registered a handler (e.g. the unreachable-service test) - nothing to remove
        }
    }

    @BeforeEach
    void stubTokens() {
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", List.of("ADMIN")));
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));
    }

    @Test
    void get_forwardsToCatalogAndReturnsItsBodyStatusAndContentTypeUnchanged() throws Exception {
        fakeCatalog.createContext("/api/catalog/resources", exchange -> {
            byte[] body = "[{\"id\":\"r1\"}]".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockMvc.perform(get("/api/catalog/resources").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value("r1"));
    }

    @Test
    void get_forwardsCallersOriginalBearerTokenToCatalogUnchanged() throws Exception {
        AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
        fakeCatalog.createContext("/api/catalog/resources", exchange -> {
            receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });

        mockMvc.perform(get("/api/catalog/resources").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());

        assertThat(receivedAuthHeader.get()).isEqualTo("Bearer admin-token");
    }

    @Test
    void post_forwardsRequestBodyRawAndReturns201WithLocationHeaderPreserved() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        fakeCatalog.createContext("/api/catalog/resources", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] responseBody = "{\"id\":\"new-id\",\"version\":0}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Location", "/api/catalog/resources/new-id");
            exchange.sendResponseHeaders(201, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        String requestBody = "{\"resourceType\":\"LABORATORIO\",\"name\":\"Lab\",\"cupo\":10}";

        mockMvc.perform(post("/api/catalog/resources")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/catalog/resources/new-id"))
                .andExpect(jsonPath("$.id").value("new-id"));
        assertThat(receivedBody.get()).isEqualTo(requestBody);
    }

    @Test
    void put_forwardsToCatalogWithIdAppendedToPath() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        fakeCatalog.createContext("/api/catalog/resources", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            byte[] responseBody = "{\"version\":1}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });

        mockMvc.perform(put("/api/catalog/resources/5f9a5c1e-2a3b-4e10-9c2f-8b6d2b6b0a11")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"cupo\":1,\"version\":0}"))
                .andExpect(status().isOk());
        assertThat(receivedPath.get()).isEqualTo("/api/catalog/resources/5f9a5c1e-2a3b-4e10-9c2f-8b6d2b6b0a11");
    }

    @Test
    void get_forwardsCatalogsOwn403WithoutReinterpretingIt() throws Exception {
        fakeCatalog.createContext("/api/catalog/resources", exchange -> {
            byte[] body = "{\"detail\":\"You do not have permission to access this resource.\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockMvc.perform(get("/api/catalog/resources").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getResources_withTecnicoRole_isAllowedThroughTheGateway() throws Exception {
        fakeCatalog.createContext("/api/catalog/resources", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });

        mockMvc.perform(get("/api/catalog/resources").header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isOk());
    }

    @Test
    void postResources_withTecnicoRole_isRejectedByTheBffBeforeReachingCatalog() throws Exception {
        mockMvc.perform(post("/api/catalog/resources")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getResources_withNoToken_returns401BeforeAnyForwarding() throws Exception {
        mockMvc.perform(get("/api/catalog/resources"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_whenCatalogIsUnreachable_returns503ProblemDetailNotAStackTrace() throws Exception {
        // Arrange: stop the fake catalog entirely (no handler, no listener) - the RestClient's
        // base URL was fixed at context startup, so restarting on the exact same port afterwards
        // (in the finally block) keeps every other test in this class able to reach it again.
        int port = fakeCatalog.getAddress().getPort();
        fakeCatalog.stop(0);
        try {
            mockMvc.perform(get("/api/catalog/resources").header("Authorization", "Bearer admin-token"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value("The catalog service is currently unavailable."))
                    .andExpect(jsonPath("$.stackTrace").doesNotExist());
        } finally {
            fakeCatalog = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            fakeCatalog.start();
        }
    }

    private static Jwt jwt(String subject, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("iss", "http://localhost:8081/realms/campuslab")
                .claim("realm_access", Map.of("roles", roles))
                .build();
    }
}
