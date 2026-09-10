package cl.campuslab.bff.bookings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * The Facade forwards request/response bodies unparsed (OWASP A08) - a real JDK
 * HttpServer stands in for ms-campuslab-bookings so these tests exercise the actual
 * RestClient plumbing (headers, body bytes, query string, status codes) rather than
 * mocking the fluent RestClient API itself, mirroring CatalogFacadeControllerTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class BookingsFacadeControllerTest {

    private static HttpServer fakeBookings;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void bookingsServiceUrl(DynamicPropertyRegistry registry) throws IOException {
        fakeBookings = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeBookings.start();
        registry.add("bookings.service-url", () -> "http://localhost:" + fakeBookings.getAddress().getPort());
    }

    @AfterEach
    void resetHandlers() {
        try {
            fakeBookings.removeContext("/api/bookings");
        } catch (IllegalArgumentException noContextRegistered) {
            // this test never registered a handler (e.g. the unreachable-service test) - nothing to remove
        }
    }

    @BeforeEach
    void stubTokens() {
        given(jwtDecoder.decode("estudiante-token")).willReturn(jwt("estudiante-uuid", List.of("ESTUDIANTE")));
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));
        given(jwtDecoder.decode("auditor-token")).willReturn(jwt("auditor-uuid", List.of("AUDITOR")));
    }

    @Test
    void post_forwardsRequestBodyRawAndReturns201WithLocationHeaderPreserved() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        fakeBookings.createContext("/api/bookings", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] responseBody = "{\"id\":\"new-id\",\"status\":\"SOLICITADA\",\"version\":0}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Location", "/api/bookings/new-id");
            exchange.sendResponseHeaders(201, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        String requestBody = "{\"resourceId\":\"5f9a5c1e-2a3b-4e10-9c2f-8b6d2b6b0a11\",\"requestedStart\":\"2026-09-15T10:00:00Z\",\"requestedEnd\":\"2026-09-15T12:00:00Z\"}";

        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer estudiante-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/bookings/new-id"))
                .andExpect(jsonPath("$.id").value("new-id"));
        assertThat(receivedBody.get()).isEqualTo(requestBody);
    }

    @Test
    void post_forwardsCallersOriginalBearerTokenToBookingsUnchanged() throws Exception {
        AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
        fakeBookings.createContext("/api/bookings", exchange -> {
            receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(201, 0);
            exchange.close();
        });

        mockMvc.perform(post("/api/bookings")
                .header("Authorization", "Bearer estudiante-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));

        assertThat(receivedAuthHeader.get()).isEqualTo("Bearer estudiante-token");
    }

    @Test
    void postBookings_withTecnicoRole_isRejectedByTheBffBeforeReachingBookings() throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_forwardsToBookingsAndReturnsItsBodyStatusAndContentTypeUnchanged() throws Exception {
        fakeBookings.createContext("/api/bookings", exchange -> {
            byte[] body = "{\"id\":\"b1\",\"status\":\"SOLICITADA\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockMvc.perform(get("/api/bookings/b1").header("Authorization", "Bearer estudiante-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("b1"));
    }

    @Test
    void get_forwardsBookingsOwn404WithoutReinterpretingIt() throws Exception {
        fakeBookings.createContext("/api/bookings", exchange -> {
            byte[] body = "{\"detail\":\"No booking with id b1\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockMvc.perform(get("/api/bookings/b1").header("Authorization", "Bearer estudiante-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_forwardsQueryStringToBookings() throws Exception {
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        fakeBookings.createContext("/api/bookings", exchange -> {
            receivedQuery.set(exchange.getRequestURI().getQuery());
            byte[] body = "[]".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockMvc.perform(get("/api/bookings?status=SOLICITADA&from=2026-09-01T00:00:00Z")
                        .header("Authorization", "Bearer estudiante-token"))
                .andExpect(status().isOk());

        assertThat(receivedQuery.get()).isEqualTo("status=SOLICITADA&from=2026-09-01T00:00:00Z");
    }

    @Test
    void list_withNoQueryParams_forwardsBarePath() throws Exception {
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        AtomicReference<Boolean> queryWasNull = new AtomicReference<>();
        fakeBookings.createContext("/api/bookings", exchange -> {
            queryWasNull.set(exchange.getRequestURI().getQuery() == null);
            byte[] body = "[]".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockMvc.perform(get("/api/bookings").header("Authorization", "Bearer estudiante-token"))
                .andExpect(status().isOk());

        assertThat(queryWasNull.get()).isTrue();
        assertThat(receivedQuery.get()).isNull();
    }

    @Test
    void getBookings_withAuditorRole_isRejectedByTheBffBeforeReachingBookings() throws Exception {
        mockMvc.perform(get("/api/bookings").header("Authorization", "Bearer auditor-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void put_forwardsToBookingsWithIdAndStatusSuffixAppendedToPath() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        fakeBookings.createContext("/api/bookings", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            byte[] responseBody = "{\"status\":\"APROBADA\",\"version\":1}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });

        mockMvc.perform(put("/api/bookings/5f9a5c1e-2a3b-4e10-9c2f-8b6d2b6b0a11/status")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APROBADA\"}"))
                .andExpect(status().isOk());
        assertThat(receivedPath.get()).isEqualTo("/api/bookings/5f9a5c1e-2a3b-4e10-9c2f-8b6d2b6b0a11/status");
    }

    @Test
    void putStatus_withAuditorRole_isRejectedByTheBffBeforeReachingBookings() throws Exception {
        mockMvc.perform(put("/api/bookings/5f9a5c1e-2a3b-4e10-9c2f-8b6d2b6b0a11/status")
                        .header("Authorization", "Bearer auditor-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APROBADA\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getBookings_withNoToken_returns401BeforeAnyForwarding() throws Exception {
        mockMvc.perform(get("/api/bookings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_whenBookingsIsUnreachable_returns503ProblemDetailNotAStackTrace() throws Exception {
        // Arrange: stop the fake bookings service entirely (no handler, no listener) - the
        // RestClient's base URL was fixed at context startup, so restarting on the exact same
        // port afterwards (in the finally block) keeps every other test in this class able to
        // reach it again.
        int port = fakeBookings.getAddress().getPort();
        fakeBookings.stop(0);
        try {
            mockMvc.perform(get("/api/bookings").header("Authorization", "Bearer estudiante-token"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value("The bookings service is currently unavailable."))
                    .andExpect(jsonPath("$.stackTrace").doesNotExist());
        } finally {
            fakeBookings = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            fakeBookings.start();
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
