package cl.campuslab.mqadmin.mq;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Validation-only unit coverage (design doc §3/§9 AC7) - the actual basicGet/basicPublish/
 * basicAck movement against a real broker is proven by MqAdminApiTest (AC5/AC6), since
 * that behaviour genuinely needs a real Channel, not a mock.
 */
@ExtendWith(MockitoExtension.class)
class RequeueServiceTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private QueueInspectionService inspectionService;

    private RequeueService service;

    @BeforeEach
    void setUp() {
        service = new RequeueService(rabbitTemplate, inspectionService);
    }

    @Test
    void requeue_withUnrecognizedDlqName_throwsUnknownDlqAndNeverTouchesRabbit() {
        assertThatThrownBy(() -> service.requeue("not.a.real.dlq", new RequeueRequest(null, true), "admin-oid"))
                .isInstanceOf(UnknownDlqException.class);
        verifyNoInteractions(rabbitTemplate, inspectionService);
    }

    @Test
    void requeue_withNeitherCountNorAll_throwsInvalidRequest() {
        assertThatThrownBy(() -> service.requeue("q.cmd.email.dlq", new RequeueRequest(null, null), "admin-oid"))
                .isInstanceOf(InvalidRequeueRequestException.class);
    }

    @Test
    void requeue_withBothCountAndAll_throwsInvalidRequest() {
        assertThatThrownBy(() -> service.requeue("q.cmd.email.dlq", new RequeueRequest(2, true), "admin-oid"))
                .isInstanceOf(InvalidRequeueRequestException.class);
    }

    @Test
    void requeue_withNullRequestBody_throwsInvalidRequest() {
        assertThatThrownBy(() -> service.requeue("q.cmd.email.dlq", null, "admin-oid"))
                .isInstanceOf(InvalidRequeueRequestException.class);
    }

    @Test
    void requeue_withZeroCount_throwsInvalidRequest() {
        assertThatThrownBy(() -> service.requeue("q.cmd.email.dlq", new RequeueRequest(0, null), "admin-oid"))
                .isInstanceOf(InvalidRequeueRequestException.class);
    }

    @Test
    void requeue_withNegativeCount_throwsInvalidRequest() {
        assertThatThrownBy(() -> service.requeue("q.cmd.email.dlq", new RequeueRequest(-3, null), "admin-oid"))
                .isInstanceOf(InvalidRequeueRequestException.class);
    }

    @Test
    void requeue_withAllFalse_throwsInvalidRequest() {
        assertThatThrownBy(() -> service.requeue("q.cmd.email.dlq", new RequeueRequest(null, false), "admin-oid"))
                .isInstanceOf(InvalidRequeueRequestException.class);
    }

    @Test
    void requeue_withValidCount_neverThrowsBeforeTouchingRabbit() {
        given(inspectionService.depthOf("q.cmd.email.dlq")).willReturn(5L, 3L);
        given(rabbitTemplate.execute(org.mockito.ArgumentMatchers.any())).willReturn(2);

        RequeueResponse response = service.requeue("q.cmd.email.dlq", new RequeueRequest(2, null), "admin-oid");

        org.assertj.core.api.Assertions.assertThat(response.dlqName()).isEqualTo("q.cmd.email.dlq");
        org.assertj.core.api.Assertions.assertThat(response.requestedCount()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(response.actualRequeuedCount()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(response.remainingInDlq()).isEqualTo(3L);
    }
}
