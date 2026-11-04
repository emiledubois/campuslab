package cl.campuslab.report.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Estado actual por reserva, alimentado solo desde {@code audit.timeline}
 * (audit-timeline-compacted.md §4.2). Se escribe unicamente con
 * {@link BookingStateProjectionRepository#upsertIfNewer}; la entidad es de solo lectura.
 * No guarda oids: misma minimizacion que {@link ReportEvent}.
 */
@Entity
@Table(name = "booking_state_projection")
public class BookingStateProjection {

    @Id
    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "resource_id", nullable = false, insertable = false, updatable = false)
    private UUID resourceId;

    @Column(name = "current_status", nullable = false, insertable = false, updatable = false, length = 20)
    private String currentStatus;

    @Column(name = "last_transition_at", nullable = false, insertable = false, updatable = false)
    private Instant lastTransitionAt;

    @Column(name = "last_event_id", nullable = false, insertable = false, updatable = false, length = 64)
    private String lastEventId;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected BookingStateProjection() {
        // JPA
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public Instant getLastTransitionAt() {
        return lastTransitionAt;
    }

    public String getLastEventId() {
        return lastEventId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
