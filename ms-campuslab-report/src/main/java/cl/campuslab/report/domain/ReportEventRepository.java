package cl.campuslab.report.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * KPI/top-resources queries are ordinary, parameterized SQL aggregate queries over the
 * raw, individually-persisted rows (design doc §2.2/§7 A03) - no rolling counters, no
 * stream-processing topology. Native queries because {@code date_trunc} has no JPQL
 * equivalent this project needs to abstract away.
 */
public interface ReportEventRepository extends JpaRepository<ReportEvent, UUID> {

    boolean existsByEventId(String eventId);

    /** {@code reservasPorHora}'s per-hour count of a given event type (design doc §2.3). */
    @Query(value = "SELECT date_trunc('hour', occurred_at) AS bucket, COUNT(*) AS cnt "
            + "FROM report_event WHERE event_type = :eventType AND occurred_at >= :from AND occurred_at < :to "
            + "GROUP BY bucket", nativeQuery = true)
    List<Object[]> countByEventTypeGroupedByHour(
            @Param("eventType") String eventType, @Param("from") Instant from, @Param("to") Instant to);

    /** {@code bookingId}/{@code occurredAt} pairs for a given event type within a range - used
     * by {@code tiempoDeCiclo} to find completed (DEVUELTA) bookings (design doc §2.3). */
    @Query(value = "SELECT booking_id, occurred_at FROM report_event "
            + "WHERE event_type = :eventType AND occurred_at >= :from AND occurred_at <= :to", nativeQuery = true)
    List<Object[]> findOccurredAtByEventTypeInRange(
            @Param("eventType") String eventType, @Param("from") Instant from, @Param("to") Instant to);

    /** {@code bookingId}/{@code occurredAt} pairs for a given event type, regardless of range -
     * used by {@code tiempoDeCiclo} to find each completed booking's own SOLICITADA timestamp,
     * which need not itself fall inside the requested range (design doc §2.3). */
    @Query(value = "SELECT booking_id, occurred_at FROM report_event "
            + "WHERE event_type = :eventType AND booking_id IN :bookingIds", nativeQuery = true)
    List<Object[]> findOccurredAtByEventTypeAndBookingIdIn(
            @Param("eventType") String eventType, @Param("bookingIds") Collection<UUID> bookingIds);

    /** Each distinct {@code bookingId}'s single latest event (resourceId/toStatus) - a live,
     * present-tense snapshot never scoped by {@code range} (design doc §2.3's {@code
     * equiposOcupados} definition, evaluated per-bookingId before being rolled up to
     * distinct resourceIds by the caller). */
    @Query(value = "SELECT re.resource_id, re.to_status FROM report_event re "
            + "INNER JOIN (SELECT booking_id, MAX(occurred_at) AS max_occurred_at "
            + "FROM report_event GROUP BY booking_id) latest "
            + "ON re.booking_id = latest.booking_id AND re.occurred_at = latest.max_occurred_at",
            nativeQuery = true)
    List<Object[]> findLatestStatusPerBooking();

    /** {@code top-resources}: ranked by approval count within range, ties broken by
     * {@code resourceId} ascending (design doc §2.3/§3). */
    @Query(value = "SELECT resource_id, COUNT(*) AS approved_count FROM report_event "
            + "WHERE event_type = :eventType AND occurred_at >= :from AND occurred_at <= :to "
            + "GROUP BY resource_id ORDER BY approved_count DESC, resource_id ASC LIMIT :limit",
            nativeQuery = true)
    List<Object[]> topResourcesByApprovedCount(
            @Param("eventType") String eventType, @Param("from") Instant from, @Param("to") Instant to,
            @Param("limit") int limit);
}
