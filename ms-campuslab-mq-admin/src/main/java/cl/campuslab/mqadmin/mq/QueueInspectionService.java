package cl.campuslab.mqadmin.mq;

import cl.campuslab.mqadmin.topology.MqTopology;
import java.util.List;
import java.util.Properties;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Service;

/**
 * AMQP-only inspection (design doc §7 A06) - {@link RabbitAdmin#getQueueProperties}
 * exposes {@code QUEUE_MESSAGE_COUNT}/{@code QUEUE_CONSUMER_COUNT} over the same
 * connection/credentials mq-admin already needs for topology declaration, no Management
 * HTTP API call anywhere. {@code getQueueProperties} itself swallows connection failures
 * and returns {@code null} (logged internally by Spring AMQP, not rethrown) - it cannot
 * be used on its own to tell "queue legitimately absent" apart from "broker unreachable",
 * so {@link #verifyBrokerReachable()} makes a deliberate, separate connectivity probe
 * first, exactly the "503 when RabbitMQ is unreachable" case the API contract requires.
 */
@Service
public class QueueInspectionService {

    private final RabbitAdmin rabbitAdmin;
    private final ConnectionFactory connectionFactory;
    private final DlqRateSampler dlqRateSampler;

    public QueueInspectionService(RabbitAdmin rabbitAdmin, ConnectionFactory connectionFactory, DlqRateSampler dlqRateSampler) {
        this.rabbitAdmin = rabbitAdmin;
        this.connectionFactory = connectionFactory;
        this.dlqRateSampler = dlqRateSampler;
    }

    public List<QueueInfo> listQueues() {
        verifyBrokerReachable();
        return MqTopology.ALL_QUEUES_IN_DISPLAY_ORDER.stream().map(this::toQueueInfo).toList();
    }

    public long depthOf(String queueName) {
        verifyBrokerReachable();
        return messageCountOf(queueName);
    }

    private QueueInfo toQueueInfo(String name) {
        boolean isDlq = MqTopology.isDlq(name);
        long messageCount = messageCountOf(name);
        int consumerCount = consumerCountOf(name);
        Double dlqRatePerMinute = isDlq ? dlqRateSampler.currentRatePerMinute(name) : null;
        return new QueueInfo(name, isDlq, messageCount, consumerCount, dlqRatePerMinute);
    }

    private long messageCountOf(String queueName) {
        Properties properties = rabbitAdmin.getQueueProperties(queueName);
        return properties != null ? toLong(properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)) : 0L;
    }

    private int consumerCountOf(String queueName) {
        Properties properties = rabbitAdmin.getQueueProperties(queueName);
        return properties != null ? toInt(properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT)) : 0;
    }

    private void verifyBrokerReachable() {
        try {
            Connection connection = connectionFactory.createConnection();
            connection.close();
        } catch (AmqpException ex) {
            throw new RabbitUnavailableException(ex);
        }
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
