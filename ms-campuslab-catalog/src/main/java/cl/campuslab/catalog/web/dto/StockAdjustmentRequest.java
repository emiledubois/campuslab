package cl.campuslab.catalog.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Body for {@code POST .../decrement} and {@code .../increment} (design doc §2.2/§3) -
 * {@code bookingId} is used exclusively as an idempotency key, stored as an opaque value,
 * never joined against any bookings-owned table (database-per-service).
 */
public record StockAdjustmentRequest(@NotNull(message = "bookingId is required") UUID bookingId) {
}
