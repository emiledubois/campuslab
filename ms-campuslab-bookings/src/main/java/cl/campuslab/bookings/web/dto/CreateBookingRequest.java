package cl.campuslab.bookings.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Bound onto by Jackson only, never onto the JPA entity directly (OWASP A08) - {@code id},
 * {@code status}, {@code studentOid}, {@code version}, {@code createdAt}/{@code updatedAt}
 * have no place in this DTO at all, so nothing a caller sends for them can ever reach the
 * entity. {@code resourceId} is accepted at face value (UUID shape only, no catalog
 * existence check) per the design doc's explicit slice-3 decision.
 */
public record CreateBookingRequest(
        @NotNull(message = "resourceId is required") UUID resourceId,
        @NotNull(message = "requestedStart is required") Instant requestedStart,
        @NotNull(message = "requestedEnd is required") Instant requestedEnd,
        @Size(max = 500, message = "notes must be at most 500 characters") String notes) {
}
