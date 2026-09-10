package cl.campuslab.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Idempotency ledger for the approval saga's stock decrement (design doc §4) -
 * {@code bookingId} is stored as an opaque value, never joined against bookings'
 * own table (database-per-service). No TTL/cleanup (§10 Open Question 4): flagged,
 * not built, at this project's scale.
 */
@Entity
@IdClass(StockDecrementLedgerKey.class)
@Table(name = "stock_decrement_ledger")
public class StockDecrementLedger {

    @Id
    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Id
    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "decremented_at", nullable = false)
    private Instant decrementedAt;

    protected StockDecrementLedger() {
        // JPA
    }

    public StockDecrementLedger(UUID resourceId, UUID bookingId) {
        this.resourceId = resourceId;
        this.bookingId = bookingId;
    }

    @PrePersist
    void onCreate() {
        this.decrementedAt = Instant.now();
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public Instant getDecrementedAt() {
        return decrementedAt;
    }
}
