package cl.campuslab.bookings.catalog;

import java.util.UUID;

/**
 * The subset of catalog's {@code CatalogResourceResponse} shape bookings actually
 * needs (design doc §7 A08) - bookings never proxies this body onward, so it is bound
 * onto this explicit DTO via Jackson rather than trusted as raw bytes.
 */
public record CatalogStockResponse(UUID id, Integer stock, Integer cupo, Long version) {
}
