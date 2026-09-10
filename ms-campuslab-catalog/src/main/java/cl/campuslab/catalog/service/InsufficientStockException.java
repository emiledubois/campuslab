package cl.campuslab.catalog.service;

/** Raised when {@code stock}/{@code cupo} is already 0 - mapped to 409 by ApiExceptionHandler. */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException() {
        super("Insufficient stock/cupo to approve this booking.");
    }
}
