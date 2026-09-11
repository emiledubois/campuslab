package cl.campuslab.kafkaadmin.topology;

import cl.campuslab.kafkaadmin.kafka.TopologyInspectionService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs once, at application startup (design doc §9 AC1) - creates any topic this
 * service owns that doesn't yet exist, then verifies every topic's config, logging the
 * outcome. Deliberately never throws out of {@link #run} - a Kafka outage at startup
 * must not prevent kafka-admin itself from coming up healthy (§9 AC1/AC2).
 */
@Component
public class TopologyStartupRunner implements ApplicationRunner {

    private final TopologyInspectionService topologyInspectionService;

    public TopologyStartupRunner(TopologyInspectionService topologyInspectionService) {
        this.topologyInspectionService = topologyInspectionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        topologyInspectionService.createAndVerifyAtStartup();
    }
}
