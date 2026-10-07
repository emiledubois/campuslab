package cl.campuslab.notify.messaging;

/**
 * The extension point {@code messaging-notify.md} §5.4/§10 already flagged as where a
 * future real SMTP/push integration would plug in (design doc §1/§6) - introduced now
 * because this slice's acceptance criteria require a transient dispatch failure to be
 * injectable deterministically in a test, without adding a test-only field to the
 * shared, cross-service message envelope. One production implementation today
 * ({@link LoggingNotificationDispatcher}); a test-only {@code ScriptableNotificationDispatcher}
 * stands in for it in {@code NotificationListenersIntegrationTest}.
 */
public interface NotificationDispatcher {

    /**
     * Performs (today: simulates/logs) the actual send. Throws {@link
     * TransientNotificationException} if the attempt failed in a way a retry might
     * recover from. Never throws {@link PoisonMessageException} - poison classification
     * happens earlier, in {@link NotificationProcessor}, before dispatch is reached.
     */
    void dispatch(NotificationType type, NotificationEnvelope envelope);
}
