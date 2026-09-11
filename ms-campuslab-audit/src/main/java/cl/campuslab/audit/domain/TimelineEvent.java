package cl.campuslab.audit.domain;

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
 * {@code id} is Hibernate-assigned (matching {@code Booking}'s own convention, design
 * doc §4) - no {@code pgcrypto} extension needed. {@code eventId} is the idempotency
 * key (§5.4): the {@code uq_timeline_event_event_id} unique constraint is what makes
 * deduplication real, not best-effort. Immutable once persisted - a timeline entry is
 * never updated, only inserted (or skipped as a duplicate).
 */
@Entity
@Table(name = "timeline_event")
public class TimelineEvent {

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

    @Column(name = "actor_oid", nullable = false, updatable = false, length = 255)
    private String actorOid;

    /** Comma-joined (design doc §4/§10 open question 6 - a deliberate minor
     * simplification, only ever displayed, never queried by individual role). */
    @Column(name = "actor_roles", nullable = false, updatable = false, length = 255)
    private String actorRoles;

    @Column(name = "student_oid", nullable = false, updatable = false, length = 255)
    private String studentOid;

    @Column(name = "from_status", updatable = false, length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, updatable = false, length = 20)
    private String toStatus;

    @Column(name = "trace_id", nullable = false, updatable = false, length = 64)
    private String traceId;

    @Column(name = "correlation_id", nullable = false, updatable = false, length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected TimelineEvent() {
        // JPA
    }

    public TimelineEvent(
            String eventId, UUID bookingId, UUID resourceId, String eventType, String actorOid, String actorRoles,
            String studentOid, String fromStatus, String toStatus, String traceId, String correlationId, Instant occurredAt) {
        this.eventId = eventId;
        this.bookingId = bookingId;
        this.resourceId = resourceId;
        this.eventType = eventType;
        this.actorOid = actorOid;
        this.actorRoles = actorRoles;
        this.studentOid = studentOid;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.traceId = traceId;
        this.correlationId = correlationId;
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

    public String getActorOid() {
        return actorOid;
    }

    public String getActorRoles() {
        return actorRoles;
    }

    public String getStudentOid() {
        return studentOid;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
