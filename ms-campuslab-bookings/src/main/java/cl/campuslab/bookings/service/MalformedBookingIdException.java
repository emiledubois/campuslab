package cl.campuslab.bookings.service;

/** Raised when a path {@code {id}} is not a valid UUID - mapped to 400 by ApiExceptionHandler. */
public class MalformedBookingIdException extends RuntimeException {

    public MalformedBookingIdException(String id) {
        super("'" + id + "' is not a valid booking id");
    }
}
