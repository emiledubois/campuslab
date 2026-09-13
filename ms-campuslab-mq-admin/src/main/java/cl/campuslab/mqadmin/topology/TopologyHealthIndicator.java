package cl.campuslab.mqadmin.topology;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Design doc (demo-readiness.md) §2/Decision 1 - makes {@code GET /actuator/health}'s
 * {@code components.topology} entry mean "the topology declare step has actually
 * completed successfully," not just "the JVM started." Reuses {@link RabbitAdmin}'s own
 * {@code initialize()} (the exact same idempotent-redeclare-safe declare logic
 * {@link RabbitTopologyConfig}'s {@code Declarables} bean already relies on, unchanged -
 * see {@code RabbitTopologyConfigTest}) plus {@code getQueueProperties} (the same call
 * {@code QueueInspectionService} already uses) as the verify step - no new declare/verify
 * logic, only a new observer of it. Retries on a fixed schedule until it succeeds once,
 * then latches {@code UP} forever (design's own "flipping an internal AtomicBoolean...
 * only on success") - this is what lets health recover automatically once RabbitMQ comes
 * back after being down at startup (AC3), with no manual intervention.
 */
@Component
public class TopologyHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(TopologyHealthIndicator.class);

    private final RabbitAdmin rabbitAdmin;
    private final AtomicBoolean topologyVerified = new AtomicBoolean(false);

    public TopologyHealthIndicator(RabbitAdmin rabbitAdmin) {
        this.rabbitAdmin = rabbitAdmin;
    }

    @Override
    public Health health() {
        if (topologyVerified.get()) {
            return Health.up().build();
        }
        return Health.down().withDetail("reason", "topology not yet declared").build();
    }

    @Scheduled(fixedDelay = 5000)
    public void checkTopology() {
        if (topologyVerified.get()) {
            return;
        }
        try {
            rabbitAdmin.initialize();
            boolean allDeclared = MqTopology.ALL_QUEUES_IN_DISPLAY_ORDER.stream()
                    .allMatch(name -> rabbitAdmin.getQueueProperties(name) != null);
            if (allDeclared) {
                topologyVerified.set(true);
                log.info("MQ topology health: outcome=[VERIFIED] status=[UP]");
            } else {
                log.error("MQ topology health: outcome=[NOT_YET_VERIFIED] status=[DOWN] "
                        + "reason=[one or more queues not present after initialize]");
            }
        } catch (Exception ex) {
            log.error("MQ topology health: outcome=[CHECK_FAILED] status=[DOWN] "
                            + "exceptionClass=[{}] exceptionMessage=[{}]",
                    ex.getClass().getName(), ex.getMessage());
        }
    }
}
