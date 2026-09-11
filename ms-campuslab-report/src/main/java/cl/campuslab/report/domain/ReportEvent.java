package cl.campuslab.report.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code id} is Hibernate-assigned (matching {@code TimelineEvent}'s/{@code Booking}'s
 * own convention, design doc §4). {@code eventId} is the idempotency key: the {@code
 * uq_report_event_event_id} unique constraint makes deduplication real, not best-effort.
 * Deliberately excludes {@code actorOid}/{@code actorRoles}/{@code studentOid}/{@code
 * traceId}/{@code correlationId} - a stricter minimization than {@code TimelineEvent}'s
 * own schema (design doc §4/§7 A02): none of the KPIs or top-resources ever need to know
 * who requested/approved/returned anything.
 */
@Entity
@Table(name = "report_event")
public class ReportEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false, length = 64)
    private String eventId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private UUID resourceId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 30)
    private String eventType;

    @Column(name = "from_status", updatable = false, length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, updatable = false, length = 20)
    private String toStatus;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected ReportEvent() {
        // JPA
    }

    public ReportEvent(
            String eventId, UUID bookingId, UUID resourceId, String eventType,
            String fromStatus, String toStatus, Instant occurredAt) {
        this.eventId = eventId;
        this.bookingId = bookingId;
        this.resourceId = resourceId;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.occurredAt = occurredAt;
    }

    @PrePersist
    void onCreate() {
        this.receivedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
