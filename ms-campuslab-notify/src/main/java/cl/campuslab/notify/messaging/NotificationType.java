package cl.campuslab.notify.messaging;

import java.util.Arrays;

/**
 * The four recognized notification types this slice's producer (bookings) ever mints
 * (design doc §5.2) - anything else is, by definition, unrecognized (§5.3's poison-
 * message rule).
 */
public enum NotificationType {
    EMAIL_APPROVED,
    EMAIL_ROOM_READY,
    EMAIL_RETURNED,
    PREP_TICKET_REQUESTED;

    public static NotificationType fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.name().equals(value))
                .findFirst()
                .orElse(null);
    }
}
