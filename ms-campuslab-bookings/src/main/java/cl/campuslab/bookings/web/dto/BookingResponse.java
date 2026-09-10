package cl.campuslab.bookings.web.dto;

import cl.campuslab.bookings.domain.Booking;
import cl.campuslab.bookings.domain.BookingStatus;
import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID resourceId,
        String studentOid,
        Instant requestedStart,
        Instant requestedEnd,
        String notes,
        BookingStatus status,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    public static BookingResponse from(Booking entity) {
        return new BookingResponse(
                entity.getId(),
                entity.getResourceId(),
                entity.getStudentOid(),
                entity.getRequestedStart(),
                entity.getRequestedEnd(),
                entity.getNotes(),
                entity.getStatus(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
