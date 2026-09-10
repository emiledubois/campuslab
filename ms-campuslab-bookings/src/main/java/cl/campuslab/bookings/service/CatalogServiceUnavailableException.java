package cl.campuslab.bookings.service;

/**
 * Catalog was unreachable, timed out, or returned an unexpected non-2xx response the
 * saga has no specific contract for - mapped to 503 by ApiExceptionHandler, the same
 * {@code ResourceAccessException}-to-{@code ProblemDetail} shape the BFF's own Facade
 * controllers already use for this failure mode (design doc §3).
 */
public class CatalogServiceUnavailableException extends RuntimeException {

    public CatalogServiceUnavailableException() {
        super("The catalog service is currently unavailable; the booking was not approved.");
    }
}
