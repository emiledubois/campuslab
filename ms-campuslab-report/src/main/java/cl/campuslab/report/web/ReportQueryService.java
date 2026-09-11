package cl.campuslab.report.web;

import cl.campuslab.report.domain.ReportEventRepository;
import cl.campuslab.report.messaging.BookingStreamEventType;
import cl.campuslab.report.web.dto.BucketResponse;
import cl.campuslab.report.web.dto.EquiposOcupadosResponse;
import cl.campuslab.report.web.dto.KpisResponse;
import cl.campuslab.report.web.dto.ReservasPorHoraResponse;
import cl.campuslab.report.web.dto.ResourceApprovalCountResponse;
import cl.campuslab.report.web.dto.TiempoDeCicloResponse;
import cl.campuslab.report.web.dto.TopResourcesResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * On-the-fly SQL aggregate queries over persisted raw events, at request time - no
 * pre-aggregated rolling counters, no stream-processing topology (design doc §2.2).
 * Every "now" reference runs through the injected {@link Clock} so the deterministic
 * KPI scenario (design doc §9 AC6) can fix time precisely.
 */
@Service
public class ReportQueryService {

    private static final int TOP_RESOURCES_LIMIT = 10;
    private static final Set<String> OCCUPIED_STATUSES = Set.of("APROBADA", "EN_PREPARACION", "EN_USO");

    private final ReportEventRepository repository;
    private final Clock clock;

    public ReportQueryService(ReportEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public KpisResponse kpis(String rangeParam) {
        ReportRange range = ReportRange.fromParam(rangeParam);
        Instant now = Instant.now(clock);

        return new KpisResponse(
                range.paramValue(),
                now,
                computeReservasPorHora(range, now),
                computeTiempoDeCiclo(range, now),
                computeEquiposOcupados(now));
    }

    public TopResourcesResponse topResources(String rangeParam) {
        ReportRange range = ReportRange.fromParam(rangeParam);
        Instant now = Instant.now(clock);
        Instant rangeStart = now.minus(range.duration());

        List<Object[]> rows = repository.topResourcesByApprovedCount(
                BookingStreamEventType.BOOKING_APROBADA, rangeStart, now, TOP_RESOURCES_LIMIT);

        List<ResourceApprovalCountResponse> resources = rows.stream()
                .map(row -> new ResourceApprovalCountResponse(toUuid(row[0]), toLong(row[1])))
                .toList();

        return new TopResourcesResponse(range.paramValue(), now, resources);
    }

    /** Hour-aligned, zero-padded buckets covering the full range, anchored to the
     * current hour's start (design doc §2.3). */
    private ReservasPorHoraResponse computeReservasPorHora(ReportRange range, Instant now) {
        Instant nowHourFloor = now.truncatedTo(ChronoUnit.HOURS);
        Instant rangeStart = nowHourFloor.minus(range.bucketCount() - 1L, ChronoUnit.HOURS);
        Instant rangeEndExclusive = nowHourFloor.plus(1, ChronoUnit.HOURS);

        List<Object[]> rows = repository.countByEventTypeGroupedByHour(
                BookingStreamEventType.BOOKING_SOLICITADA, rangeStart, rangeEndExclusive);

        Map<Instant, Long> countsByHour = new HashMap<>();
        for (Object[] row : rows) {
            countsByHour.put(toInstant(row[0]), toLong(row[1]));
        }

        List<BucketResponse> buckets = new ArrayList<>(range.bucketCount());
        for (int i = 0; i < range.bucketCount(); i++) {
            Instant hourStart = rangeStart.plus(i, ChronoUnit.HOURS);
            buckets.add(new BucketResponse(hourStart, countsByHour.getOrDefault(hourStart, 0L)));
        }
        return new ReservasPorHoraResponse(range.bucketCount(), buckets);
    }

    /** Average seconds between SOLICITADA and DEVUELTA for bookings that reached
     * DEVUELTA within the range - the SOLICITADA event's own timestamp need not itself
     * fall inside the range (design doc §2.3). */
    private TiempoDeCicloResponse computeTiempoDeCiclo(ReportRange range, Instant now) {
        Instant rangeStart = now.minus(range.duration());

        List<Object[]> devueltaRows = repository.findOccurredAtByEventTypeInRange(
                BookingStreamEventType.BOOKING_DEVUELTA, rangeStart, now);
        if (devueltaRows.isEmpty()) {
            return new TiempoDeCicloResponse("seconds", null, 0);
        }

        Map<UUID, Instant> devueltaByBooking = new HashMap<>();
        for (Object[] row : devueltaRows) {
            devueltaByBooking.put(toUuid(row[0]), toInstant(row[1]));
        }

        List<Object[]> solicitadaRows = repository.findOccurredAtByEventTypeAndBookingIdIn(
                BookingStreamEventType.BOOKING_SOLICITADA, devueltaByBooking.keySet());
        Map<UUID, Instant> solicitadaByBooking = new HashMap<>();
        for (Object[] row : solicitadaRows) {
            solicitadaByBooking.put(toUuid(row[0]), toInstant(row[1]));
        }

        long totalSeconds = 0;
        long completedCount = 0;
        for (Map.Entry<UUID, Instant> entry : devueltaByBooking.entrySet()) {
            Instant solicitadaAt = solicitadaByBooking.get(entry.getKey());
            if (solicitadaAt == null) {
                // No matching SOLICITADA for this bookingId (e.g. lost to a sustained
                // Kafka outage, or arrived before this consumer group existed) - cannot
                // contribute a duration without both endpoints (design doc §2.3).
                continue;
            }
            totalSeconds += entry.getValue().getEpochSecond() - solicitadaAt.getEpochSecond();
            completedCount++;
        }

        if (completedCount == 0) {
            return new TiempoDeCicloResponse("seconds", null, 0);
        }
        double average = (double) totalSeconds / completedCount;
        return new TiempoDeCicloResponse("seconds", average, completedCount);
    }

    /** A live snapshot, never scoped by {@code range} - per-bookingId latest status
     * first, then rolled up to distinct resourceIds (design doc §2.3's two-step
     * rollup: a resource with one open booking and one completed booking still
     * counts as occupied). */
    private EquiposOcupadosResponse computeEquiposOcupados(Instant now) {
        List<Object[]> latestPerBooking = repository.findLatestStatusPerBooking();

        Set<UUID> occupiedResourceIds = new LinkedHashSet<>();
        for (Object[] row : latestPerBooking) {
            String toStatus = (String) row[1];
            if (OCCUPIED_STATUSES.contains(toStatus)) {
                occupiedResourceIds.add(toUuid(row[0]));
            }
        }

        List<UUID> resourceIds = new ArrayList<>(occupiedResourceIds);
        return new EquiposOcupadosResponse(now, resourceIds.size(), resourceIds);
    }

    private static UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }

    private static long toLong(Object value) {
        return ((Number) value).longValue();
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        throw new IllegalStateException("Unexpected timestamp type: " + value.getClass());
    }
}
