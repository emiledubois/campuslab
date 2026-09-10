package cl.campuslab.notify.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationTypeTest {

    @Test
    void fromValue_withRecognizedType_returnsMatchingEnum() {
        NotificationType type = NotificationType.fromValue("EMAIL_APPROVED");

        assertThat(type).isEqualTo(NotificationType.EMAIL_APPROVED);
    }

    @Test
    void fromValue_withUnrecognizedType_returnsNull() {
        NotificationType type = NotificationType.fromValue("FOO");

        assertThat(type).isNull();
    }

    @Test
    void fromValue_withNull_returnsNull() {
        NotificationType type = NotificationType.fromValue(null);

        assertThat(type).isNull();
    }
}
