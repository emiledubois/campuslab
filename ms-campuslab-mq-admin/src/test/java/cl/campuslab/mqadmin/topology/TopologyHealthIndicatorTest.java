package cl.campuslab.mqadmin.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.actuate.health.Status;

/**
 * Design doc (demo-readiness.md) §9 AC3 - proves {@code TopologyHealthIndicator} genuinely
 * gates on the declare/verify step's own outcome, not just "the JVM started."
 */
@ExtendWith(MockitoExtension.class)
class TopologyHealthIndicatorTest {

    @Mock
    private RabbitAdmin rabbitAdmin;

    private TopologyHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new TopologyHealthIndicator(rabbitAdmin);
    }

    @Test
    void health_beforeAnyCheck_reportsDown() {
        org.springframework.boot.actuate.health.Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "topology not yet declared");
    }

    @Test
    void checkTopology_whenBrokerUnreachable_leavesHealthDown() {
        willThrow(new AmqpConnectException(new RuntimeException("refused"))).given(rabbitAdmin).initialize();

        indicator.checkTopology();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void checkTopology_whenSomeQueueMissingAfterInitialize_leavesHealthDown() {
        given(rabbitAdmin.getQueueProperties(org.mockito.ArgumentMatchers.anyString())).willReturn(null);

        indicator.checkTopology();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        verify(rabbitAdmin).initialize();
    }

    @Test
    void checkTopology_whenDeclareAndVerifySucceed_flipsHealthUpAndStaysUp() {
        given(rabbitAdmin.getQueueProperties(org.mockito.ArgumentMatchers.anyString())).willReturn(new Properties());

        indicator.checkTopology();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

        // A second check must never re-declare once already verified (the "latch" behaviour
        // demo-readiness.md's Decision 1 specifies: flips to true only once, on success).
        indicator.checkTopology();
        verify(rabbitAdmin).initialize();
    }
}
