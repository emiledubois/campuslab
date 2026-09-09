package cl.campuslab.bookings.service;

/**
 * Raised for a malformed {@code from}/{@code to} instant, or {@code from > to}, on
 * GET /api/bookings - mapped to 400 by ApiExceptionHandler.
 */
public class InvalidBookingQueryException extends RuntimeException {

    public InvalidBookingQueryException(String message) {
        super(message);
    }
}
