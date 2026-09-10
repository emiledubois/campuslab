package cl.campuslab.catalog.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite {@code @IdClass} key for {@link StockDecrementLedger} - see design doc §4. */
public class StockDecrementLedgerKey implements Serializable {

    private UUID resourceId;
    private UUID bookingId;

    public StockDecrementLedgerKey() {
        // JPA
    }

    public StockDecrementLedgerKey(UUID resourceId, UUID bookingId) {
        this.resourceId = resourceId;
        this.bookingId = bookingId;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StockDecrementLedgerKey that)) {
            return false;
        }
        return Objects.equals(resourceId, that.resourceId) && Objects.equals(bookingId, that.bookingId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resourceId, bookingId);
    }
}
