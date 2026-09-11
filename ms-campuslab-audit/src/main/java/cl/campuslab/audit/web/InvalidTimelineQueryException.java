package cl.campuslab.audit.web;

/**
 * Every {@code GET /api/audit/timeline} 400 case (design doc §3) - unrecognized {@code
 * eventType}, unparseable {@code bookingId}/{@code from}/{@code to}, or {@code from}
 * after {@code to}.
 */
public class InvalidTimelineQueryException extends RuntimeException {

    public InvalidTimelineQueryException(String message) {
        super(message);
    }
}
