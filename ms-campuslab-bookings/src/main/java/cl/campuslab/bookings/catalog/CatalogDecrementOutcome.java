package cl.campuslab.bookings.catalog;

/** Mirrors design doc §3/§7 A09's exact outcome vocabulary for {@code catalogDecrementResult}. */
public enum CatalogDecrementOutcome {
    SUCCESS,
    INSUFFICIENT_STOCK,
    NOT_FOUND,
    UNREACHABLE,
    UNEXPECTED_ERROR
}
