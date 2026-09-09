package cl.campuslab.bookings.service;

import cl.campuslab.bookings.domain.BookingStatus;

/**
 * Raised when {@code (currentStatus -> requestedStatus)} is not a legal edge in the
 * state machine for the caller's role - mapped to 409 by ApiExceptionHandler, with a
 * detail message distinct from the optimistic-lock 409 (see design doc §3 step 6 vs
 * step 7, and AC19).
 */
public class IllegalBookingTransitionException extends RuntimeException {

    public IllegalBookingTransitionException(BookingStatus from, BookingStatus to) {
        super("Cannot transition a booking from " + from + " to " + to + ".");
    }
}
