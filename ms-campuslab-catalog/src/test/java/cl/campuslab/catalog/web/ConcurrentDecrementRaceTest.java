package cl.campuslab.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import cl.campuslab.catalog.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Design doc §9 AC3 / §10 Open Question 3: catalog's own atomic-decrement race, required
 * and proven against real concurrent HTTP + Postgres, exactly mirroring
 * ConcurrentUpdateRaceTest's existing shape but against the new {@code /decrement}
 * endpoint - two distinct bookingIds against one resource with exactly 1 unit of stock,
 * released by a shared latch so the two decrement calls genuinely race on the wire, not
 * simulated sequentially.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentDecrementRaceTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", List.of("ADMIN")));
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void twoConcurrentDecrements_onResourceWithOneUnitLeft_exactlyOneSucceeds() throws Exception {
        // Arrange: one EQUIPO resource with exactly 1 unit of stock, two distinct
        // bookingIds racing to decrement it (design doc §7 A04's "last unit" scenario).
        String id = createResource();
        String bookingA = UUID.randomUUID().toString();
        String bookingB = UUID.randomUUID().toString();
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<ResponseEntity<String>> raceA = () -> {
            startLatch.await();
            return decrement(id, bookingA);
        };
        Callable<ResponseEntity<String>> raceB = () -> {
            startLatch.await();
            return decrement(id, bookingB);
        };

        // Act: release both threads at the same instant so they genuinely race on the wire.
        Future<ResponseEntity<String>> futureA = executor.submit(raceA);
        Future<ResponseEntity<String>> futureB = executor.submit(raceB);
        startLatch.countDown();
        ResponseEntity<String> resultA = futureA.get(30, TimeUnit.SECONDS);
        ResponseEntity<String> resultB = futureB.get(30, TimeUnit.SECONDS);

        // Assert: exactly one 200 (stock -> 0), exactly one 409, never two 200s and never
        // a negative stock.
        List<HttpStatus> statuses = List.of(
                HttpStatus.valueOf(resultA.getStatusCode().value()),
                HttpStatus.valueOf(resultB.getStatusCode().value()));
        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);

        ResponseEntity<String> winner = resultA.getStatusCode().is2xxSuccessful() ? resultA : resultB;
        JsonNode winnerBody = objectMapper.readTree(winner.getBody());
        assertThat(winnerBody.get("stock").asInt()).isZero();

        ResponseEntity<String> refreshed = restTemplate.exchange(
                "/api/catalog/resources", HttpMethod.GET, new HttpEntity<>(authHeaders("admin-token")), String.class);
        JsonNode list = objectMapper.readTree(refreshed.getBody());
        int finalStock = -1;
        for (JsonNode node : list) {
            if (node.get("id").asText().equals(id)) {
                finalStock = node.get("stock").asInt();
            }
        }
        assertThat(finalStock).isZero();
    }

    private String createResource() throws Exception {
        HttpHeaders headers = authHeaders("admin-token");
        String body = """
                {"resourceType":"EQUIPO","name":"Microscopio concurrencia","description":"desc","location":"loc","stock":1}
                """;
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/catalog/resources", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        JsonNode json = objectMapper.readTree(response.getBody());
        return json.get("id").asText();
    }

    private ResponseEntity<String> decrement(String id, String bookingId) {
        HttpHeaders headers = authHeaders("tecnico-token");
        String body = "{\"bookingId\":\"" + bookingId + "\"}";
        return restTemplate.exchange(
                "/api/catalog/resources/" + id + "/decrement", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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
