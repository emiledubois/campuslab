package cl.campuslab.mqadmin.mq;

import cl.campuslab.mqadmin.topology.MqTopology;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A deliberately minimal, in-memory DLQ-rate approximation (design doc §7 A06/§10 open
 * question 1) - not a RabbitMQ-computed statistic: a periodic depth snapshot per DLQ,
 * rate = positive depth delta / elapsed minutes since the previous snapshot. Resets to
 * zero on every mq-admin restart; that is an accepted, documented limitation, not a bug.
 */
@Component
public class DlqRateSampler {

    private static final Logger log = LoggerFactory.getLogger(DlqRateSampler.class);

    private final RabbitAdmin rabbitAdmin;
    private final ConcurrentHashMap<String, Long> lastDepth = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastSampledAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> currentRatePerMinute = new ConcurrentHashMap<>();

    public DlqRateSampler(RabbitAdmin rabbitAdmin) {
        this.rabbitAdmin = rabbitAdmin;
        MqTopology.DLQ_NAMES.forEach(dlqName -> currentRatePerMinute.put(dlqName, 0.0));
    }

    @Scheduled(fixedRateString = "${mq-admin.dlq-sample-rate-ms:30000}")
    public void sample() {
        Instant now = Instant.now();
        for (String dlqName : MqTopology.DLQ_NAMES) {
            try {
                sampleOne(dlqName, now);
            } catch (AmqpException ex) {
                log.warn("DLQ rate sampling failed: dlqName=[{}] reason=[{}]", dlqName, ex.getMessage());
            }
        }
    }

    private void sampleOne(String dlqName, Instant now) {
        Properties properties = rabbitAdmin.getQueueProperties(dlqName);
        long depth = properties != null ? toLong(properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)) : 0L;

        Long previousDepth = lastDepth.get(dlqName);
        Instant previousSampledAt = lastSampledAt.get(dlqName);
        if (previousDepth != null && previousSampledAt != null) {
            double elapsedMinutes = Duration.between(previousSampledAt, now).toMillis() / 60_000.0;
            if (elapsedMinutes > 0) {
                double positiveDelta = Math.max(0, depth - previousDepth);
                currentRatePerMinute.put(dlqName, positiveDelta / elapsedMinutes);
            }
        }
        lastDepth.put(dlqName, depth);
        lastSampledAt.put(dlqName, now);
    }

    public double currentRatePerMinute(String dlqName) {
        return currentRatePerMinute.getOrDefault(dlqName, 0.0);
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
