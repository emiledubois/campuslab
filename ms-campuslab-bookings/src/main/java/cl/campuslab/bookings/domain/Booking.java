package cl.campuslab.bookings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code id} is application-assigned (Hibernate's built-in UUID generator), same
 * IDOR-mitigation rationale as catalog (non-enumerable ids). {@code resourceId} is a
 * plain opaque UUID - no foreign key, no cross-service join (database-per-service).
 * {@code studentSub} is the IdP's {@code sub} claim, set exactly once at creation from
 * the validated JWT and never updatable thereafter - it is the field the ownership
 * check in the service layer is built on.
 */
@Entity
@Table(name = "booking")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private UUID resourceId;

    @Column(name = "student_sub", nullable = false, updatable = false, length = 255)
    private String studentSub;

    @Column(name = "requested_start", nullable = false)
    private Instant requestedStart;

    @Column(name = "requested_end", nullable = false)
    private Instant requestedEnd;

    @Column(length = 500)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Booking() {
        // JPA
    }

    public Booking(UUID resourceId, String studentSub, Instant requestedStart, Instant requestedEnd, String notes) {
        this.resourceId = resourceId;
        this.studentSub = studentSub;
        this.requestedStart = requestedStart;
        this.requestedEnd = requestedEnd;
        this.notes = notes;
        this.status = BookingStatus.SOLICITADA;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void transitionTo(BookingStatus newStatus) {
        this.status = newStatus;
    }

    public UUID getId() {
        return id;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getStudentSub() {
        return studentSub;
    }

    public Instant getRequestedStart() {
        return requestedStart;
    }

    public Instant getRequestedEnd() {
        return requestedEnd;
    }

    public String getNotes() {
        return notes;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
