package cl.campuslab.bookings.service;

/**
 * Catalog's decrement returned 409 (no stock/cupo left, or lost the optimistic-lock race
 * to a concurrent decrement) - mapped to 409 by ApiExceptionHandler (design doc §3).
 */
public class CatalogInsufficientStockException extends RuntimeException {

    public CatalogInsufficientStockException() {
        super("Cannot approve this booking: the referenced resource has no stock/cupo available.");
    }
}
