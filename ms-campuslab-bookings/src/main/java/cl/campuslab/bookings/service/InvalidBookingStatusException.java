package cl.campuslab.bookings.service;

/**
 * Raised when a {@code status} value (in the PUT .../status body, or the GET list's
 * {@code status} query param) is not one of the six {@code BookingStatus} enum
 * literals - mapped to 400 by ApiExceptionHandler. Note: every one of the six real
 * literals (including {@code SOLICITADA}, which is never a legal PUT target) parses
 * successfully here; an illegal-but-real target is a 409 raised later, not a 400.
 */
public class InvalidBookingStatusException extends RuntimeException {

    public InvalidBookingStatusException(String status) {
        super("'" + status + "' is not a recognized booking status");
    }
}
