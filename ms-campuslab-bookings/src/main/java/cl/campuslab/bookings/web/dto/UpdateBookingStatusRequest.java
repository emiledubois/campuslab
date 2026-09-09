package cl.campuslab.bookings.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code status} is bound as a plain string, not directly as the {@code BookingStatus}
 * enum - this lets the controller distinguish "missing" (caught here by
 * {@code @NotBlank}, 400) from "present but not a recognized literal" (parsed explicitly
 * in the controller, also 400 but via a different, more specific exception), matching
 * the design doc's exact two-step check order for this field.
 */
public record UpdateBookingStatusRequest(@NotBlank(message = "status is required") String status) {
}
