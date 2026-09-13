package cl.campuslab.kafkaadmin.topology;

import cl.campuslab.kafkaadmin.kafka.TopicInfo;
import cl.campuslab.kafkaadmin.kafka.TopologyInspectionService;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Design doc (demo-readiness.md) §2/Decision 1 - makes {@code GET /actuator/health}'s
 * {@code components.topology} entry mean "the topology create-and-verify step has
 * actually completed successfully," not just "the JVM started." Reuses {@link
 * TopologyInspectionService}'s own existing, unchanged {@code createAndVerifyAtStartup()}
 * (create) and {@code listTopics()} (verify) - the same public methods {@link
 * TopologyStartupRunner} and {@code KafkaAdminController} already call - no new
 * declare/verify logic, only a new observer of it. Retries on a fixed schedule until every
 * topic's {@code configMatches()} is true, then latches {@code UP} forever (design's own
 * "flipping an internal AtomicBoolean... only on success") - this is what lets health
 * recover automatically once Kafka comes back after being down at startup (AC3), with no
 * manual intervention.
 */
@Component
public class TopologyHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(TopologyHealthIndicator.class);

    private final TopologyInspectionService topologyInspectionService;
    private final AtomicBoolean topologyVerified = new AtomicBoolean(false);

    public TopologyHealthIndicator(TopologyInspectionService topologyInspectionService) {
        this.topologyInspectionService = topologyInspectionService;
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
            topologyInspectionService.createAndVerifyAtStartup();
            List<TopicInfo> topics = topologyInspectionService.listTopics();
            boolean allMatch = !topics.isEmpty() && topics.stream().allMatch(TopicInfo::configMatches);
            if (allMatch) {
                topologyVerified.set(true);
                log.info("Kafka topology health: outcome=[VERIFIED] status=[UP]");
            } else {
                log.error("Kafka topology health: outcome=[NOT_YET_VERIFIED] status=[DOWN]");
            }
        } catch (Exception ex) {
            log.error("Kafka topology health: outcome=[CHECK_FAILED] status=[DOWN] "
                            + "exceptionClass=[{}] exceptionMessage=[{}]",
                    ex.getClass().getName(), ex.getMessage());
        }
    }
}
