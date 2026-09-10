package cl.campuslab.bookings.service;

/**
 * Catalog has no such {@code resourceId} (a real, expected case per bookings-core.md §3's
 * deliberate decision not to validate {@code resourceId} at creation time) - mapped to
 * 409, not 404: this endpoint's 404 is reserved for "no such booking"/ownership-masking
 * (design doc §3).
 */
public class CatalogResourceNotFoundException extends RuntimeException {

    public CatalogResourceNotFoundException() {
        super("Cannot approve this booking: the referenced catalog resource does not exist.");
    }
}
