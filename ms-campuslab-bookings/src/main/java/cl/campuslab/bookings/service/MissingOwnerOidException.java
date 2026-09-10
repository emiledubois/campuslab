package cl.campuslab.bookings.service;

/**
 * Raised when an ESTUDIANTE-role caller's otherwise-valid, role-checked token has no
 * {@code oid} claim. An Entra access token for a real user is defined to carry
 * {@code oid}; its absence indicates a malformed/wrong-kind token (e.g. a
 * client-credentials/app-only token with no {@code oid}), which must never be treated
 * as a valid student identity - there is deliberately no fallback to {@code sub} here,
 * unlike the display-only fields in /api/me (see docs/designs/entra-migration.md §4).
 * Mapped to 401 by ApiExceptionHandler, not 403/404: this is an authentication-quality
 * failure of the token itself, not an authorization or ownership-mismatch outcome.
 */
public class MissingOwnerOidException extends RuntimeException {

    public MissingOwnerOidException() {
        super("Token is missing the required 'oid' claim");
    }
}
