package cl.campuslab.kafkaadmin.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import cl.campuslab.kafkaadmin.AbstractIntegrationTest;
import cl.campuslab.kafkaadmin.topology.KafkaTopology;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Proves the real behaviour design doc §9 AC1/AC2 describe, against a real (Testcontainers)
 * Kafka broker - never mocked, since {@code AdminClient.createTopics} against an
 * already-existing topic is a real, broker-verified {@code TopicExistsException} that a
 * mock could only assert by construction, not prove (design doc §5.1's own "investigated,
 * not assumed" framing).
 */
@SpringBootTest
class TopologyInspectionServiceApiTest extends AbstractIntegrationTest {

    @Autowired
    private TopologyInspectionService topologyInspectionService;

    @Autowired
    private Admin adminClient;

    // This test proves business logic against a real broker (KafkaContainer), not JWT
    // validation (proven independently by EntraJwtValidationTest/SecurityIntegrationTest)
    // - mocked here purely to avoid an unrelated real network call to a fake issuer URL.
    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void createAndVerifyAtStartup_calledTwice_neverThrowsAndTopicsStayVerified() {
        topologyInspectionService.createAndVerifyAtStartup();

        topologyInspectionService.createAndVerifyAtStartup();

        List<TopicInfo> topics = topologyInspectionService.listTopics();
        assertThat(topics).hasSize(KafkaTopology.ALL_TOPICS.size());
        assertThat(topics).allSatisfy(topic -> assertThat(topic.configMatches()).isTrue());
    }

    @Test
    void listTopics_afterStartup_reportsExpectedPartitionsAndCleanupPolicyPerTopic() {
        topologyInspectionService.createAndVerifyAtStartup();

        List<TopicInfo> topics = topologyInspectionService.listTopics();

        TopicInfo bookingsEvents = topics.stream()
                .filter(topic -> topic.name().equals(KafkaTopology.TOPIC_BOOKINGS_EVENTS))
                .findFirst().orElseThrow();
        assertThat(bookingsEvents.actualPartitions()).isEqualTo(3);
        assertThat(bookingsEvents.actualCleanupPolicy()).isEqualTo(TopicConfig.CLEANUP_POLICY_DELETE);
        assertThat(bookingsEvents.configMatches()).isTrue();
    }

    @Test
    void listTopics_afterConfigDriftsFromExpected_reportsConfigMismatchWithoutThrowing() throws Exception {
        topologyInspectionService.createAndVerifyAtStartup();
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, KafkaTopology.TOPIC_BOOKINGS_EVENTS);

        try {
            alterRetentionMs(resource, "999999");

            List<TopicInfo> topics = topologyInspectionService.listTopics();

            TopicInfo bookingsEvents = topics.stream()
                    .filter(topic -> topic.name().equals(KafkaTopology.TOPIC_BOOKINGS_EVENTS))
                    .findFirst().orElseThrow();
            assertThat(bookingsEvents.configMatches()).isFalse();
            assertThat(bookingsEvents.actualRetentionMs()).isEqualTo(999999L);
            assertThat(bookingsEvents.expectedRetentionMs()).isEqualTo(432_000_000L);
        } finally {
            // The Kafka broker is a shared, static Testcontainer across every test method
            // in this class (AbstractIntegrationTest's own singleton-container rationale) -
            // this restores the topic's config so sibling tests never observe the drift
            // this test deliberately introduces.
            alterRetentionMs(resource, "432000000");
        }
    }

    private void alterRetentionMs(ConfigResource resource, String retentionMs) throws Exception {
        AlterConfigOp alteration = new AlterConfigOp(
                new ConfigEntry(TopicConfig.RETENTION_MS_CONFIG, retentionMs), AlterConfigOp.OpType.SET);
        adminClient.incrementalAlterConfigs(Map.of(resource, List.of(alteration)))
                .all().get(5, TimeUnit.SECONDS);
    }
}
