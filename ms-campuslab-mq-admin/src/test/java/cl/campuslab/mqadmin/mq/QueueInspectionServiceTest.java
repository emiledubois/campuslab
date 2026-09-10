package cl.campuslab.mqadmin.mq;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

/**
 * The 503 mapping (design doc §3's "RabbitMQ unreachable from mq-admin") is proven here
 * with a mocked, deliberately-failing ConnectionFactory - the accuracy/consumer-count
 * behaviour against a real broker (AC2/AC3) is proven separately by MqAdminApiTest, since
 * {@code getQueueProperties} itself swallows connection failures (this class's own
 * javadoc) and a mock alone couldn't distinguish that from the real thing.
 */
@ExtendWith(MockitoExtension.class)
class QueueInspectionServiceTest {

    @Mock
    private RabbitAdmin rabbitAdmin;

    @Mock
    private ConnectionFactory connectionFactory;

    @Mock
    private DlqRateSampler dlqRateSampler;

    @Test
    void listQueues_whenBrokerUnreachable_throwsRabbitUnavailable() {
        given(connectionFactory.createConnection()).willThrow(new AmqpConnectException(new RuntimeException("refused")));
        QueueInspectionService service = new QueueInspectionService(rabbitAdmin, connectionFactory, dlqRateSampler);

        assertThatThrownBy(service::listQueues).isInstanceOf(RabbitUnavailableException.class);
    }

    @Test
    void depthOf_whenBrokerUnreachable_throwsRabbitUnavailable() {
        given(connectionFactory.createConnection()).willThrow(new AmqpConnectException(new RuntimeException("refused")));
        QueueInspectionService service = new QueueInspectionService(rabbitAdmin, connectionFactory, dlqRateSampler);

        assertThatThrownBy(() -> service.depthOf("q.cmd.email.dlq")).isInstanceOf(RabbitUnavailableException.class);
    }
}
