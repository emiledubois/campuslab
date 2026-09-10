package cl.campuslab.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import cl.campuslab.catalog.AbstractIntegrationTest;
import cl.campuslab.catalog.domain.StockDecrementLedgerKey;
import cl.campuslab.catalog.domain.StockDecrementLedgerRepository;
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
 * Design doc §9 AC7a (Revision 1) - the same-{@code bookingId} counterpart of
 * {@link ConcurrentDecrementRaceTest}, confined to catalog's own layer, against real
 * Postgres: proves catalog itself never lets a shared {@code bookingId} cause two
 * independent physical decrements, however the two calls interleave (both may commit as
 * one genuine write plus one idempotent replay, or one may lose the resource row's own
 * {@code @Version} race before either commits and get a clean 409 - both are valid
 * outcomes; only the final stock value and ledger row count are asserted).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentSameBookingDecrementRaceTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StockDecrementLedgerRepository ledgerRepository;

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
    void twoConcurrentDecrements_withIdenticalBookingId_appliesExactlyOnePhysicalDecrement() throws Exception {
        // Arrange: one EQUIPO resource with abundant stock (this race is about
        // idempotency-under-concurrency, not exhaustion - AC3 already covers exhaustion)
        // and the identical bookingId used by both concurrent decrement calls.
        String id = createResource();
        UUID resourceId = UUID.fromString(id);
        UUID bookingId = UUID.randomUUID();
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<ResponseEntity<String>> raceA = () -> {
            startLatch.await();
            return decrement(id, bookingId.toString());
        };
        Callable<ResponseEntity<String>> raceB = () -> {
            startLatch.await();
            return decrement(id, bookingId.toString());
        };

        // Act: release both threads at the same instant so they genuinely race on the wire.
        Future<ResponseEntity<String>> futureA = executor.submit(raceA);
        Future<ResponseEntity<String>> futureB = executor.submit(raceB);
        startLatch.countDown();
        ResponseEntity<String> resultA = futureA.get(30, TimeUnit.SECONDS);
        ResponseEntity<String> resultB = futureB.get(30, TimeUnit.SECONDS);

        // Assert: each response is 200 or 409 - the exact pairing is legitimately
        // non-deterministic under real timing (both-200 via one genuine write plus one
        // idempotent replay, or one-200-one-409 via a lost @Version race before either
        // commits, are both valid outcomes).
        List<HttpStatus> statuses = List.of(
                HttpStatus.valueOf(resultA.getStatusCode().value()),
                HttpStatus.valueOf(resultB.getStatusCode().value()));
        assertThat(statuses).allMatch(status -> status == HttpStatus.OK || status == HttpStatus.CONFLICT);

        ResponseEntity<String> refreshed = restTemplate.exchange(
                "/api/catalog/resources", HttpMethod.GET, new HttpEntity<>(authHeaders("admin-token")), String.class);
        JsonNode list = objectMapper.readTree(refreshed.getBody());
        int finalStock = -1;
        for (JsonNode node : list) {
            if (node.get("id").asText().equals(id)) {
                finalStock = node.get("stock").asInt();
            }
        }
        assertThat(finalStock).isEqualTo(4);

        // Scoped to this resourceId, not a global count() - the shared Testcontainers
        // Postgres instance (AbstractIntegrationTest's singleton-container pattern) is
        // not truncated between test classes, so other tests' ledger rows persist across
        // the same run.
        assertThat(ledgerRepository.existsById(new StockDecrementLedgerKey(resourceId, bookingId))).isTrue();
        long rowsForThisResource = ledgerRepository.findAll().stream()
                .filter(row -> row.getResourceId().equals(resourceId))
                .count();
        assertThat(rowsForThisResource).isEqualTo(1);
    }

    private String createResource() throws Exception {
        HttpHeaders headers = authHeaders("admin-token");
        String body = """
                {"resourceType":"EQUIPO","name":"Microscopio concurrencia mismo booking","description":"desc","location":"loc","stock":5}
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
