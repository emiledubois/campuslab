package cl.campuslab.bookings.service;

import java.util.UUID;

/**
 * Raised both for a genuinely unknown {@code id} and, for ESTUDIANTE callers only,
 * for a booking that exists but belongs to a different student - deliberately the
 * same exception/status (404, never 403) for both cases, so an ESTUDIANTE probing
 * another student's booking id cannot distinguish "wrong owner" from "not found"
 * (OWASP A01 IDOR mitigation, see design doc §3).
 */
public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(UUID id) {
        super("No booking with id " + id);
    }
}
