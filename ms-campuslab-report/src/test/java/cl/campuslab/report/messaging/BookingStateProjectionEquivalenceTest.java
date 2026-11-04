package cl.campuslab.report.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import cl.campuslab.report.AbstractIntegrationTest;
import cl.campuslab.report.domain.BookingStateProjection;
import cl.campuslab.report.domain.BookingStateProjectionRepository;
import cl.campuslab.report.domain.ReportEvent;
import cl.campuslab.report.domain.ReportEventRepository;
import cl.campuslab.report.web.ReportQueryService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * audit-timeline-compacted.md §9 AC 14: con los mismos hechos en {@code report_event} y sus
 * snapshots equivalentes en la proyeccion, los recursos ocupados coinciden con
 * {@code equiposOcupados}. Postgres real; el broker no se usa (direccion inalcanzable de la
 * config de test).
 */
@SpringBootTest
class BookingStateProjectionEquivalenceTest extends AbstractIntegrationTest {

    private static final Set<String> OCCUPIED = Set.of("APROBADA", "EN_PREPARACION", "EN_USO");

    @Autowired
    private ReportEventRepository reportEventRepository;

    @Autowired
    private BookingStateProjectionRepository projectionRepository;

    @Autowired
    private BookingStateProjectionService projectionService;

    @Autowired
    private ReportQueryService queryService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        // Postgres compartido entre clases de test: limpiar antes y despues.
        reportEventRepository.deleteAll();
        projectionRepository.deleteAll();
    }

    @Test
    void occupiedResourcesFromProjection_equalEquiposOcupadosFromReportEvents() {
        // Arrange
        Instant h0 = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        UUID r3 = UUID.randomUUID();
        UUID r4 = UUID.randomUUID();
        fact(UUID.randomUUID(), r1, h0, "SOLICITADA", "APROBADA", "EN_PREPARACION");
        fact(UUID.randomUUID(), r2, h0.plusSeconds(60), "SOLICITADA", "CANCELADA");
        fact(UUID.randomUUID(), r3, h0.plusSeconds(120), "SOLICITADA", "APROBADA", "EN_PREPARACION", "EN_USO", "DEVUELTA");
        fact(UUID.randomUUID(), r3, h0.plusSeconds(180), "SOLICITADA", "APROBADA", "EN_PREPARACION", "EN_USO");
        fact(UUID.randomUUID(), r4, h0.plusSeconds(240), "SOLICITADA");

        // Act
        Set<UUID> fromProjection = projectionRepository.findAll().stream()
                .filter(row -> OCCUPIED.contains(row.getCurrentStatus()))
                .map(BookingStateProjection::getResourceId)
                .collect(Collectors.toSet());
        Set<UUID> fromKpi = Set.copyOf(queryService.kpis("last24h").equiposOcupados().resourceIds());

        // Assert
        assertThat(fromProjection).containsExactlyInAnyOrder(r1, r3);
        assertThat(fromProjection).isEqualTo(fromKpi);
    }

    /** Cada transicion entra a report_event (como desde bookings.events) y a la proyeccion (como desde audit.timeline). */
    private void fact(UUID bookingId, UUID resourceId, Instant start, String... statuses) {
        String from = null;
        for (int i = 0; i < statuses.length; i++) {
            Instant at = start.plusSeconds(i * 5L);
            String sourceEventId = UUID.randomUUID().toString();
            reportEventRepository.save(new ReportEvent(
                    sourceEventId, bookingId, resourceId, "BOOKING_" + statuses[i], from, statuses[i], at));
            projectionService.apply(bookingId.toString(), new BookingStateSnapshotEnvelope(
                    BookingStateSnapshotEnvelope.TYPE, "derived-" + sourceEventId, Instant.now(), "trace",
                    bookingId.toString(), new BookingStateSnapshotPayload(bookingId, resourceId, statuses[i], at)));
            from = statuses[i];
        }
    }
}
