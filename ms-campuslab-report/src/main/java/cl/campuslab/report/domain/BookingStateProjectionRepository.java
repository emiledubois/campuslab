package cl.campuslab.report.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingStateProjectionRepository extends JpaRepository<BookingStateProjection, UUID> {

    boolean existsByLastEventId(String lastEventId);

    /**
     * Inserta o actualiza solo si el evento entrante es estrictamente mas nuevo. Devuelve 1 si
     * aplico y 0 si era atrasado o igual. La comparacion vive en una sola sentencia atomica para
     * seguir siendo correcta con dos consumidores en un rebalanceo (audit-timeline-compacted.md §4.2).
     */
    @Modifying
    @Query(value = "INSERT INTO booking_state_projection "
            + "(booking_id, resource_id, current_status, last_transition_at, last_event_id, updated_at) "
            + "VALUES (:bookingId, :resourceId, :currentStatus, :lastTransitionAt, :lastEventId, now()) "
            + "ON CONFLICT (booking_id) DO UPDATE SET "
            + "resource_id = EXCLUDED.resource_id, current_status = EXCLUDED.current_status, "
            + "last_transition_at = EXCLUDED.last_transition_at, last_event_id = EXCLUDED.last_event_id, updated_at = now() "
            + "WHERE booking_state_projection.last_transition_at < EXCLUDED.last_transition_at",
            nativeQuery = true)
    int upsertIfNewer(@Param("bookingId") UUID bookingId, @Param("resourceId") UUID resourceId,
                      @Param("currentStatus") String currentStatus, @Param("lastTransitionAt") Instant lastTransitionAt,
                      @Param("lastEventId") String lastEventId);
}
