package cl.campuslab.kafkaadmin.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import cl.campuslab.kafkaadmin.kafka.KafkaUnavailableException;
import cl.campuslab.kafkaadmin.kafka.TopicInfo;
import cl.campuslab.kafkaadmin.kafka.TopologyInspectionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Status;

/**
 * Design doc (demo-readiness.md) §9 AC3 - proves {@code TopologyHealthIndicator} genuinely
 * gates on the create-and-verify step's own outcome, not just "the JVM started."
 */
@ExtendWith(MockitoExtension.class)
class TopologyHealthIndicatorTest {

    @Mock
    private TopologyInspectionService topologyInspectionService;

    private TopologyHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new TopologyHealthIndicator(topologyInspectionService);
    }

    @Test
    void health_beforeAnyCheck_reportsDown() {
        org.springframework.boot.actuate.health.Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "topology not yet declared");
    }

    @Test
    void checkTopology_whenBrokerUnreachable_leavesHealthDown() {
        given(topologyInspectionService.listTopics()).willThrow(new KafkaUnavailableException(new RuntimeException("refused")));

        indicator.checkTopology();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        verify(topologyInspectionService).createAndVerifyAtStartup();
    }

    @Test
    void checkTopology_whenSomeTopicConfigMismatched_leavesHealthDown() {
        given(topologyInspectionService.listTopics()).willReturn(List.of(
                topicInfo("t.a", true), topicInfo("t.b", false)));

        indicator.checkTopology();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void checkTopology_whenAllTopicsVerified_flipsHealthUpAndStaysUp() {
        given(topologyInspectionService.listTopics()).willReturn(List.of(
                topicInfo("t.a", true), topicInfo("t.b", true)));

        indicator.checkTopology();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

        // A second check must never re-run create-and-verify once already verified (the
        // "latch" behaviour demo-readiness.md's Decision 1 specifies: flips to true only
        // once, on success).
        indicator.checkTopology();
        verify(topologyInspectionService).createAndVerifyAtStartup();
    }

    private static TopicInfo topicInfo(String name, boolean matches) {
        return new TopicInfo(name, 3, 3, 1, 1, "delete", "delete", 1000L, 1000L, matches);
    }
}
