package cl.campuslab.notify;

import cl.campuslab.notify.messaging.NotificationDispatcher;
import cl.campuslab.notify.messaging.NotificationEnvelope;
import cl.campuslab.notify.messaging.NotificationType;
import cl.campuslab.notify.messaging.TransientNotificationException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only {@link NotificationDispatcher} (design doc §2/§7.1's seam) - lets a test
 * script exactly how many times {@code dispatch()} should throw {@link
 * TransientNotificationException} for a given {@code eventId} before succeeding, without
 * any test-only field on the shared message envelope. Thread-safe (the real listener
 * container runs with concurrency 3-5), keyed on {@code eventId} so different deliveries
 * in the same test don't interfere with each other.
 */
public class ScriptableNotificationDispatcher implements NotificationDispatcher {

    private final Map<String, AtomicInteger> failuresRemaining = new ConcurrentHashMap<>();

    /** The next {@code times} calls to {@code dispatch()} for this eventId throw;
     *  calls after that succeed. {@code times = Integer.MAX_VALUE} simulates an
     *  always-failing dispatch (design doc AC6). */
    public void failNextCalls(String eventId, int times) {
        failuresRemaining.put(eventId, new AtomicInteger(times));
    }

    @Override
    public void dispatch(NotificationType type, NotificationEnvelope envelope) {
        AtomicInteger remaining = failuresRemaining.get(envelope.eventId());
        if (remaining != null && remaining.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0) {
            throw new TransientNotificationException(
                    "Simulated transient dispatch failure for test.", envelope.correlationId());
        }
    }
}
