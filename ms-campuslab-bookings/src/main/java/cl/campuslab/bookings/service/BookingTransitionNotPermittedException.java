package cl.campuslab.bookings.service;

import cl.campuslab.bookings.domain.BookingStatus;

/**
 * Raised when an ESTUDIANTE targets any status other than CANCELADA on an
 * otherwise-real, owned booking - mapped to 403 by ApiExceptionHandler. This is a
 * role-appropriateness failure on a legal-to-view booking, distinct from both the
 * 404 ownership mask (step before this one) and the 409 state-machine conflict
 * (step after this one) - see design doc §3's check order.
 */
public class BookingTransitionNotPermittedException extends RuntimeException {

    public BookingTransitionNotPermittedException(BookingStatus targetStatus) {
        super("ESTUDIANTE cannot transition a booking to " + targetStatus);
    }
}
