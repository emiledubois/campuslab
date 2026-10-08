package cl.campuslab.bookings.messaging;

/**
 * The four notification types bookings ever mints (design doc §2.2/§5.2) - each one maps
 * to exactly the channel its consuming queue is bound to on {@code cmd.direct} in
 * mq-admin's topology. The routing-key string itself lives in {@link
 * BookingsRabbitProperties} (centralized, design doc mq-names-domain-separation.md §9
 * AC4), not here - this enum only says which channel each type belongs to.
 */
public enum NotificationType {
    EMAIL_APPROVED(Channel.EMAIL),
    EMAIL_ROOM_READY(Channel.EMAIL),
    EMAIL_RETURNED(Channel.EMAIL),
    PREP_TICKET_REQUESTED(Channel.PREP);

    private final Channel channel;

    NotificationType(Channel channel) {
        this.channel = channel;
    }

    public Channel channel() {
        return channel;
    }

    public enum Channel {
        EMAIL,
        PREP
    }
}
