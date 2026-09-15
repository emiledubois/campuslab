package cl.campuslab.report.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * With {@code reportKafkaListenerContainerFactory}'s {@code autoStartup(false)}, no
 * container ever starts during context refresh, broker reachable or not - something
 * must start it once the broker actually is reachable. This is that something, on the
 * same 5-second cadence as kafka-admin's {@code TopologyHealthIndicator}.
 *
 * <p>Verified Spring Kafka 3.3.16 behavior this must work around:
 * {@code ConcurrentMessageListenerContainer.doStart()} sets the OUTER container's own
 * {@code isRunning()} flag true BEFORE it attempts to start its child
 * {@code KafkaMessageListenerContainer} (design doc Part 9). A failed start therefore
 * leaves {@code isRunning()==true} with zero consumers actually running - a naive
 * "if (!container.isRunning()) container.start()" retry would attempt once and then
 * silently never retry again. Calling {@code stop()} in the catch block resets the flag
 * (verified safe: {@code ConcurrentMessageListenerContainer.doStop()} is a no-op-safe
 * path when its child-container list is empty, which it is after a failed start) so the
 * next tick's {@code isRunning()} check is accurate again.
 */
@Component
public class KafkaListenerStartupRetryTask {

    private static final Logger log = LoggerFactory.getLogger(KafkaListenerStartupRetryTask.class);

    private final KafkaListenerEndpointRegistry registry;

    public KafkaListenerStartupRetryTask(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(fixedDelay = 5000)
    public void ensureListenersStarted() {
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (!container.isRunning()) {
                try {
                    container.start();
                    log.info("Kafka listener container id=[{}] started", container.getListenerId());
                } catch (Exception ex) {
                    log.warn("Kafka listener container id=[{}] failed to start, will retry: "
                                    + "exceptionClass=[{}] exceptionMessage=[{}]",
                            container.getListenerId(), ex.getClass().getName(), ex.getMessage());
                    try {
                        container.stop();
                    } catch (Exception stopEx) {
                        log.warn("Kafka listener container id=[{}] cleanup-stop after a failed "
                                        + "start also threw: exceptionClass=[{}] exceptionMessage=[{}]",
                                container.getListenerId(), stopEx.getClass().getName(), stopEx.getMessage());
                    }
                }
            }
        }
    }
}
