package cl.campuslab.bookings.messaging;

/**
 * All six booking-lifecycle events (design doc §5.2) - broader than {@link
 * NotificationType}'s scope: {@code bookings.events} is "fuente de verdad de eventos de
 * reserva" (every reservation event, full stop), including creation ({@code
 * BOOKING_SOLICITADA}) and {@code BOOKING_CANCELADA}, neither of which slice 5's
 * RabbitMQ notifications ever fired for.
 */
public enum BookingStreamEventType {
    BOOKING_SOLICITADA,
    BOOKING_APROBADA,
    BOOKING_EN_PREPARACION,
    BOOKING_EN_USO,
    BOOKING_DEVUELTA,
    BOOKING_CANCELADA
}
