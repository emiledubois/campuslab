package cl.campuslab.notify.messaging;

import java.util.Map;

/**
 * The project-wide messaging envelope (CLAUDE.md/design doc §5.2), deserialized as-is
 * from an incoming message body - {@code payload} is deliberately a loose {@code
 * Map<String, Object>}, not a typed payload class: notify only ever reads a couple of
 * fields out of it for logging (design doc §5.4), so a strict payload DTO would be
 * speculative structure this slice doesn't need.
 */
public record NotificationEnvelope(
        String type,
        String eventId,
        String timestamp,
        String traceId,
        String correlationId,
        Map<String, Object> payload) {

    public boolean hasAllRequiredFields() {
        return isPresent(type) && isPresent(eventId) && isPresent(timestamp) && isPresent(traceId) && isPresent(correlationId);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
