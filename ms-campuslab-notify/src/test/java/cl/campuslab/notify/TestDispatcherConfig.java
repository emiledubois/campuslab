package cl.campuslab.notify;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Swaps the production {@code LoggingNotificationDispatcher} bean for {@link
 * ScriptableNotificationDispatcher} (design doc §2) so {@code
 * NotificationListenersIntegrationTest} can inject deterministic transient failures
 * against the real Testcontainers broker. {@code @Primary} resolves the ambiguity since
 * {@code LoggingNotificationDispatcher} is still a {@code @Component} on the test
 * classpath too (not removed - it is still the real production bean).
 */
@TestConfiguration
public class TestDispatcherConfig {

    @Bean
    @Primary
    public ScriptableNotificationDispatcher scriptableNotificationDispatcher() {
        return new ScriptableNotificationDispatcher();
    }
}
