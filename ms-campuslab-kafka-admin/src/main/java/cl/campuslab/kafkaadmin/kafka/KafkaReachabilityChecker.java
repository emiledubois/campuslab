package cl.campuslab.kafkaadmin.kafka;

import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.stereotype.Component;

/**
 * Shared "is the broker actually reachable" probe (design doc §3's "503: Kafka
 * unreachable from kafka-admin") - every inspection endpoint (topics/consumer-groups/
 * dlt) calls this first, the direct analogue of mq-admin's own {@code
 * verifyBrokerReachable}. A missing topic/group is a legitimate empty result; an
 * unreachable broker is a 503, and the two must never be confused.
 */
@Component
public class KafkaReachabilityChecker {

    private static final long TIMEOUT_SECONDS = 5;

    private final Admin adminClient;

    public KafkaReachabilityChecker(Admin adminClient) {
        this.adminClient = adminClient;
    }

    public void verifyReachable() {
        try {
            adminClient.describeCluster().clusterId().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new KafkaUnavailableException(ex);
        }
    }
}
