package cl.campuslab.bookings.service;

/**
 * Raised when {@code requestedEnd} is not strictly after {@code requestedStart} on
 * POST /api/bookings - mapped to 400 by ApiExceptionHandler. The DB's
 * {@code chk_booking_window} CHECK constraint is a defense-in-depth backstop for this
 * same rule, not the primary gate.
 */
public class InvalidBookingWindowException extends RuntimeException {

    public InvalidBookingWindowException(String message) {
        super(message);
    }
}
