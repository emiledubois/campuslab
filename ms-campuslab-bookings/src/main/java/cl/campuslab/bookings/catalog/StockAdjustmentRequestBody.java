package cl.campuslab.bookings.catalog;

import java.util.UUID;

/** Outgoing body for catalog's {@code .../decrement} and {@code .../increment} (design doc §3). */
record StockAdjustmentRequestBody(UUID bookingId) {
}
