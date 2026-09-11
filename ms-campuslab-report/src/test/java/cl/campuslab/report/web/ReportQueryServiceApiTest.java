package cl.campuslab.report.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.campuslab.report.AbstractIntegrationTest;
import cl.campuslab.report.domain.ReportEvent;
import cl.campuslab.report.domain.ReportEventRepository;
import cl.campuslab.report.messaging.BookingStreamEventType;
import cl.campuslab.report.web.dto.KpisResponse;
import cl.campuslab.report.web.dto.TopResourcesResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves design doc §9 AC6 (the deterministic KPI scenario) exactly, plus AC8/AC9
 * (invalid/default range) and the zero-padding/null-average/never-zero-padded-
 * top-resources rules from §2.3 - against a real Postgres (Testcontainers), never
 * mocked, since these are genuine SQL aggregate queries (date_trunc, joins, grouping)
 * that a mocked repository could only assert by construction, not prove. A fixed
 * {@link Clock}, overriding {@code ClockConfig}'s production bean, makes "now"
 * deterministic regardless of how long the surrounding test suite takes to run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ReportQueryServiceApiTest extends AbstractIntegrationTest {

    // H0 = "the start of the current hour at test time" (design doc §9 AC6). The fixed
    // clock is set 6h after H0 - safely past every synthetic event's timestamp (the
    // latest, D's DEVUELTA, is at H0+5h) - so every event is unambiguously "in the past"
    // relative to "now", independent of how long the real test run takes.
    private static final Instant H0 = Instant.now().truncatedTo(ChronoUnit.HOURS);
    private static final Instant FIXED_NOW = H0.plus(6, ChronoUnit.HOURS);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private ReportEventRepository repository;

    @Autowired
    private ReportQueryService queryService;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        // The Testcontainers Postgres is a shared, static singleton across every
        // AbstractIntegrationTest subclass in this JVM fork (same rationale as audit's
        // own) - a @BeforeEach cleanup (not just @AfterEach) guards this class's
        // exact-count assertions against leftover rows from a sibling test class
        // (e.g. ReportKafkaConsumerApiTest's real-Kafka events) that happened to run
        // first in the same suite.
        repository.deleteAll();
    }

    @Test
    void kpis_deterministicScenario_exactNumbers() {
        UUID resourceR1 = UUID.randomUUID();
        UUID resourceR2 = UUID.randomUUID();
        UUID resourceR3 = UUID.randomUUID();
        UUID bookingA = UUID.randomUUID();
        UUID bookingB = UUID.randomUUID();
        UUID bookingC = UUID.randomUUID();
        UUID bookingD = UUID.randomUUID();

        // Booking A (R1): full cycle, 2h duration.
        save(bookingA, resourceR1, BookingStreamEventType.BOOKING_SOLICITADA, null, "SOLICITADA", H0);
        save(bookingA, resourceR1, BookingStreamEventType.BOOKING_APROBADA, "SOLICITADA", "APROBADA", H0.plus(10, ChronoUnit.MINUTES));
        save(bookingA, resourceR1, BookingStreamEventType.BOOKING_EN_PREPARACION, "APROBADA", "EN_PREPARACION", H0.plus(20, ChronoUnit.MINUTES));
        save(bookingA, resourceR1, BookingStreamEventType.BOOKING_EN_USO, "EN_PREPARACION", "EN_USO", H0.plus(30, ChronoUnit.MINUTES));
        save(bookingA, resourceR1, BookingStreamEventType.BOOKING_DEVUELTA, "EN_USO", "DEVUELTA", H0.plus(2, ChronoUnit.HOURS));

        // Booking B (R1, concurrent): still open (EN_PREPARACION).
        save(bookingB, resourceR1, BookingStreamEventType.BOOKING_SOLICITADA, null, "SOLICITADA", H0.plus(5, ChronoUnit.MINUTES));
        save(bookingB, resourceR1, BookingStreamEventType.BOOKING_APROBADA, "SOLICITADA", "APROBADA", H0.plus(15, ChronoUnit.MINUTES));
        save(bookingB, resourceR1, BookingStreamEventType.BOOKING_EN_PREPARACION, "APROBADA", "EN_PREPARACION", H0.plus(25, ChronoUnit.MINUTES));

        // Booking C (R2): cancelled, never approved.
        save(bookingC, resourceR2, BookingStreamEventType.BOOKING_SOLICITADA, null, "SOLICITADA", H0.plus(1, ChronoUnit.HOURS));
        save(bookingC, resourceR2, BookingStreamEventType.BOOKING_CANCELADA, "SOLICITADA", "CANCELADA",
                H0.plus(1, ChronoUnit.HOURS).plus(5, ChronoUnit.MINUTES));

        // Booking D (R3): full cycle, 2h duration.
        save(bookingD, resourceR3, BookingStreamEventType.BOOKING_SOLICITADA, null, "SOLICITADA", H0.plus(3, ChronoUnit.HOURS));
        save(bookingD, resourceR3, BookingStreamEventType.BOOKING_APROBADA, "SOLICITADA", "APROBADA",
                H0.plus(3, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES));
        save(bookingD, resourceR3, BookingStreamEventType.BOOKING_EN_PREPARACION, "APROBADA", "EN_PREPARACION",
                H0.plus(3, ChronoUnit.HOURS).plus(20, ChronoUnit.MINUTES));
        save(bookingD, resourceR3, BookingStreamEventType.BOOKING_EN_USO, "EN_PREPARACION", "EN_USO",
                H0.plus(3, ChronoUnit.HOURS).plus(30, ChronoUnit.MINUTES));
        save(bookingD, resourceR3, BookingStreamEventType.BOOKING_DEVUELTA, "EN_USO", "DEVUELTA", H0.plus(5, ChronoUnit.HOURS));

        KpisResponse response = queryService.kpis("last24h");

        assertThat(response.reservasPorHora().bucketCount()).isEqualTo(24);
        assertThat(bucketCountAt(response, H0)).isEqualTo(2);
        assertThat(bucketCountAt(response, H0.plus(1, ChronoUnit.HOURS))).isEqualTo(1);
        assertThat(bucketCountAt(response, H0.plus(3, ChronoUnit.HOURS))).isEqualTo(1);
        long zeroBuckets = response.reservasPorHora().buckets().stream()
                .filter(bucket -> !bucket.hourStart().equals(H0)
                        && !bucket.hourStart().equals(H0.plus(1, ChronoUnit.HOURS))
                        && !bucket.hourStart().equals(H0.plus(3, ChronoUnit.HOURS)))
                .filter(bucket -> bucket.count() != 0)
                .count();
        assertThat(zeroBuckets).isZero();

        assertThat(response.tiempoDeCiclo().completedBookingsCount()).isEqualTo(2);
        assertThat(response.tiempoDeCiclo().averageSeconds()).isEqualTo(7200.0);

        assertThat(response.equiposOcupados().count()).isEqualTo(1);
        assertThat(response.equiposOcupados().resourceIds()).containsExactly(resourceR1);

        TopResourcesResponse topResources = queryService.topResources("last7d");
        assertThat(topResources.resources()).hasSize(2);
        assertThat(topResources.resources().get(0).resourceId()).isEqualTo(resourceR1);
        assertThat(topResources.resources().get(0).approvedCount()).isEqualTo(2);
        assertThat(topResources.resources().get(1).resourceId()).isEqualTo(resourceR3);
        assertThat(topResources.resources().get(1).approvedCount()).isEqualTo(1);
        assertThat(topResources.resources()).noneMatch(r -> r.resourceId().equals(resourceR2));
    }

    @Test
    void kpis_noPersistedEvents_zeroPadsEveryBucketAndReportsNullAverage() {
        KpisResponse response = queryService.kpis("last24h");

        assertThat(response.reservasPorHora().bucketCount()).isEqualTo(24);
        assertThat(response.reservasPorHora().buckets()).hasSize(24);
        assertThat(response.reservasPorHora().buckets()).allMatch(bucket -> bucket.count() == 0);
        assertThat(response.tiempoDeCiclo().averageSeconds()).isNull();
        assertThat(response.tiempoDeCiclo().completedBookingsCount()).isZero();
        assertThat(response.equiposOcupados().count()).isZero();
        assertThat(response.equiposOcupados().resourceIds()).isEmpty();
    }

    @Test
    void topResources_tieBrokenByResourceIdAscending() {
        // Fixed, low-valued UUIDs (top bit clear in every byte) so "ascending" is
        // unambiguous under both Postgres' own byte-wise ORDER BY and this assertion -
        // two random UUIDs can disagree on ordering between Java's UUID.compareTo()
        // (signed-long comparison) and Postgres' byte-wise comparison.
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");

        save(UUID.randomUUID(), second, BookingStreamEventType.BOOKING_APROBADA, "SOLICITADA", "APROBADA", H0);
        save(UUID.randomUUID(), first, BookingStreamEventType.BOOKING_APROBADA, "SOLICITADA", "APROBADA", H0);

        TopResourcesResponse response = queryService.topResources("last7d");

        assertThat(response.resources()).hasSize(2);
        assertThat(response.resources().get(0).resourceId()).isEqualTo(first);
        assertThat(response.resources().get(1).resourceId()).isEqualTo(second);
    }

    @Test
    void getKpis_invalidRange_returns400NamingAcceptedValues() throws Exception {
        given(jwtDecoder.decode("admin-token")).willReturn(adminJwt());

        mockMvc.perform(get("/api/report/kpis?range=lastYear").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("last24h")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("last7d")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("last30d")));
    }

    @Test
    void getTopResources_invalidRange_returns400() throws Exception {
        given(jwtDecoder.decode("admin-token")).willReturn(adminJwt());

        mockMvc.perform(get("/api/report/top-resources?range=forever").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getKpis_noRangeParam_defaultsToLast24h() throws Exception {
        given(jwtDecoder.decode("admin-token")).willReturn(adminJwt());

        mockMvc.perform(get("/api/report/kpis").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("last24h"));
    }

    @Test
    void getTopResources_noRangeParam_defaultsToLast7d() throws Exception {
        given(jwtDecoder.decode("admin-token")).willReturn(adminJwt());

        mockMvc.perform(get("/api/report/top-resources").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("last7d"));
    }

    private static Jwt adminJwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("admin-sub")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("oid", "admin-oid")
                .claim("iss", "https://login.microsoftonline.com/test-tenant/v2.0")
                .claim("roles", List.of("ADMIN"))
                .build();
    }

    private void save(UUID bookingId, UUID resourceId, String eventType, String fromStatus, String toStatus, Instant occurredAt) {
        repository.save(new ReportEvent(UUID.randomUUID().toString(), bookingId, resourceId, eventType, fromStatus, toStatus, occurredAt));
    }

    private static long bucketCountAt(KpisResponse response, Instant hourStart) {
        return response.reservasPorHora().buckets().stream()
                .filter(bucket -> bucket.hourStart().equals(hourStart))
                .findFirst()
                .orElseThrow()
                .count();
    }
}
