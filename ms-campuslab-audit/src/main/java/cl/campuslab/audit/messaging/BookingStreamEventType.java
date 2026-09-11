package cl.campuslab.audit.messaging;

import java.util.Set;

/**
 * The six recognized {@code bookings.events} types (design doc §3/§5.2) - copied, not
 * shared, from {@code ms-campuslab-bookings}' own enum, per the no-cross-service-code
 * convention. Any other value is a poison message (design doc §5.4, {@code
 * UNRECOGNIZED_TYPE}).
 */
public final class BookingStreamEventType {

    public static final String BOOKING_SOLICITADA = "BOOKING_SOLICITADA";
    public static final String BOOKING_APROBADA = "BOOKING_APROBADA";
    public static final String BOOKING_EN_PREPARACION = "BOOKING_EN_PREPARACION";
    public static final String BOOKING_EN_USO = "BOOKING_EN_USO";
    public static final String BOOKING_DEVUELTA = "BOOKING_DEVUELTA";
    public static final String BOOKING_CANCELADA = "BOOKING_CANCELADA";

    public static final Set<String> RECOGNIZED = Set.of(
            BOOKING_SOLICITADA, BOOKING_APROBADA, BOOKING_EN_PREPARACION, BOOKING_EN_USO, BOOKING_DEVUELTA, BOOKING_CANCELADA);

    private BookingStreamEventType() {
    }
}
