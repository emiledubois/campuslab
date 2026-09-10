package cl.campuslab.bookings.messaging;

/**
 * The four notification types bookings ever mints (design doc §2.2/§5.2) - each one maps
 * to exactly the routing key its consuming queue is bound to on {@code cmd.direct} in
 * mq-admin's topology (copied, not shared, from that mapping - the two services must
 * agree on the wire contract, not on Java code). Copied, not shared, from
 * ms-campuslab-notify's own {@code NotificationType} per the no-cross-service-code
 * convention already established for the security packages.
 */
public enum NotificationType {
    EMAIL_APPROVED("email.send"),
    EMAIL_ROOM_READY("email.send"),
    EMAIL_RETURNED("email.send"),
    PREP_TICKET_REQUESTED("prep.ticket");

    private final String routingKey;

    NotificationType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
