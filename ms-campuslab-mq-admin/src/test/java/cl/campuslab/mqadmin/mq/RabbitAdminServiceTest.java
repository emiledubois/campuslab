package cl.campuslab.mqadmin.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Guard-logic unit coverage (design doc §9 AC18) - a mocked {@code RabbitAdmin}, no real
 * broker. Every managed name must be rejected by every relevant guarded operation before
 * RabbitAdmin is ever touched; happy-path behaviour against a non-managed name is covered
 * here too since it needs no real broker either (the AMQP-call-movement itself - real
 * queue/exchange/binding existence - is proven against a real broker by MqAdminApiTest).
 */
@ExtendWith(MockitoExtension.class)
class RabbitAdminServiceTest {

    @Mock
    private RabbitAdmin rabbitAdmin;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private RabbitAdminService service;

    private static final List<String> MANAGED_QUEUE_NAMES = List.of(
            "q.cmd.email", "q.cmd.email.dlq", "q.cmd.prep", "q.cmd.prep.dlq", "q.cmd.voucher", "q.cmd.voucher.dlq");

    private static final List<String> MANAGED_EXCHANGE_NAMES = List.of("cmd.direct", "cmd.topic", "cmd.dead.dlx");

    static Stream<String> managedQueueNames() {
        return MANAGED_QUEUE_NAMES.stream();
    }

    static Stream<String> managedExchangeNames() {
        return MANAGED_EXCHANGE_NAMES.stream();
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new RabbitAdminService(rabbitAdmin, rabbitTemplate);
    }

    @ParameterizedTest
    @MethodSource("managedQueueNames")
    void createQueue_withManagedName_throwsProtectedAndNeverTouchesRabbit(String managedName) {
        assertThatThrownBy(() -> service.createQueue(new CreateQueueRequest(managedName, true, false, false)))
                .isInstanceOf(ProtectedTopologyResourceException.class)
                .hasMessageContaining(managedName);
        verifyNoInteractions(rabbitAdmin, rabbitTemplate);
    }

    @ParameterizedTest
    @MethodSource("managedQueueNames")
    void deleteQueue_withManagedName_throwsProtectedAndNeverTouchesRabbit(String managedName) {
        assertThatThrownBy(() -> service.deleteQueue(managedName))
                .isInstanceOf(ProtectedTopologyResourceException.class)
                .hasMessageContaining(managedName);
        verifyNoInteractions(rabbitAdmin, rabbitTemplate);
    }

    @ParameterizedTest
    @MethodSource("managedQueueNames")
    void purgeQueue_withManagedName_throwsProtectedAndNeverTouchesRabbit(String managedName) {
        assertThatThrownBy(() -> service.purgeQueue(managedName))
                .isInstanceOf(ProtectedTopologyResourceException.class)
                .hasMessageContaining(managedName);
        verifyNoInteractions(rabbitAdmin, rabbitTemplate);
    }

    @ParameterizedTest
    @MethodSource("managedExchangeNames")
    void createExchange_withManagedName_throwsProtectedAndNeverTouchesRabbit(String managedName) {
        assertThatThrownBy(() -> service.createExchange(new CreateExchangeRequest(managedName, "direct", true, false)))
                .isInstanceOf(ProtectedTopologyResourceException.class)
                .hasMessageContaining(managedName);
        verifyNoInteractions(rabbitAdmin, rabbitTemplate);
    }

    @ParameterizedTest
    @MethodSource("managedExchangeNames")
    void deleteExchange_withManagedName_throwsProtectedAndNeverTouchesRabbit(String managedName) {
        assertThatThrownBy(() -> service.deleteExchange(managedName))
                .isInstanceOf(ProtectedTopologyResourceException.class)
                .hasMessageContaining(managedName);
        verifyNoInteractions(rabbitAdmin, rabbitTemplate);
    }

    @ParameterizedTest
    @MethodSource("managedExchangeNames")
    void createBinding_withManagedSourceExchange_throwsProtectedAndNeverTouchesRabbit(String managedExchange) {
        BindingRequest request = new BindingRequest(managedExchange, "q.demo.ops", "QUEUE", "x");

        assertThatThrownBy(() -> service.createBinding(request)).isInstanceOf(ProtectedTopologyResourceException.class);
        verifyNoInteractions(rabbitAdmin, rabbitTemplate);
    }

    @ParameterizedTest
    @MethodSource("managedQueueNames")
    void createBinding_withManagedQueueDestination_throwsProtectedAndNeverTouchesRabbit(String managedQueue) {
        BindingRequest request = new BindingRequest("demo.ops.exchange", managedQueue, "QUEUE", "x");

        assertThatThrownBy(() -> service.createBinding(request)).isInstanceOf(ProtectedTopologyResourceException.class);
        verifyNoInteractions(rabbitAdmin, rabbitTemplate);
    }

    @ParameterizedTest
    @MethodSource("managedExchangeNames")
    void createBinding_withManagedExchangeDestination_throwsProtectedAndNeverTouchesRabbit(String managedExchange) {
        BindingRequest request = new BindingRequest("demo.ops.exchange", managedExchange, "EXCHANGE", "x");

        assertThatThrownBy(() -> service.createBinding(request)).isInstanceOf(ProtectedTopologyResourceException.class);
        verifyNoInteractions(rabbitAdmin, rabbitTemplate);
    }

    @ParameterizedTest
    @MethodSource("managedExchangeNames")
    void deleteBinding_withManagedSourceExchange_throwsProtectedAndNeverTouchesRabbit(String managedExchange) {
        BindingRequest request = new BindingRequest(managedExchange, "q.demo.ops", "QUEUE", "x");

        assertThatThrownBy(() -> service.deleteBinding(request)).isInstanceOf(ProtectedTopologyResourceException.class);
        verifyNoInteractions(rabbitAdmin, rabbitTemplate);
    }

    @Test
    void createQueue_withNonManagedNameAndOmittedFlags_resolvesDurableTrueAndOthersFalse() {
        given(rabbitAdmin.declareQueue(any(Queue.class))).willReturn("q.demo.ops");

        AdminQueueInfo result = service.createQueue(new CreateQueueRequest("q.demo.ops", null, null, null));

        assertThat(result).isEqualTo(new AdminQueueInfo("q.demo.ops", true, false, false, 0, 0));
        verify(rabbitAdmin).declareQueue(any(Queue.class));
    }

    @Test
    void createQueue_withExplicitFalseDurable_resolvesToFalse() {
        AdminQueueInfo result = service.createQueue(new CreateQueueRequest("q.demo.ops", false, true, true));

        assertThat(result).isEqualTo(new AdminQueueInfo("q.demo.ops", false, true, true, 0, 0));
    }

    @Test
    void deleteQueue_withNonManagedNameThatDoesNotExist_throwsNotFoundWithoutCallingDelete() {
        given(rabbitAdmin.getQueueProperties("q.demo.ops")).willReturn(null);

        assertThatThrownBy(() -> service.deleteQueue("q.demo.ops")).isInstanceOf(AdminResourceNotFoundException.class);
        verify(rabbitAdmin, never()).deleteQueue(anyString());
    }

    @Test
    void deleteQueue_withNonManagedNameThatExists_succeeds() {
        given(rabbitAdmin.getQueueProperties("q.demo.ops")).willReturn(new java.util.Properties());

        service.deleteQueue("q.demo.ops");

        verify(rabbitAdmin).deleteQueue("q.demo.ops");
    }

    @Test
    void purgeQueue_withNonManagedName_returnsPurgedCount() {
        given(rabbitAdmin.purgeQueue("q.demo.ops")).willReturn(3);

        PurgeResult result = service.purgeQueue("q.demo.ops");

        assertThat(result).isEqualTo(new PurgeResult("q.demo.ops", 3));
    }

    @Test
    void createExchange_withNonManagedNameAndDirectType_declaresDirectExchange() {
        AdminExchangeInfo result = service.createExchange(new CreateExchangeRequest("demo.ops.exchange", "direct", null, null));

        assertThat(result).isEqualTo(new AdminExchangeInfo("demo.ops.exchange", "direct", true, false));
        verify(rabbitAdmin).declareExchange(any(Exchange.class));
    }

    @Test
    void deleteExchange_withNonManagedNameThatExists_succeeds() {
        given(rabbitTemplate.execute(any())).willReturn(true);

        service.deleteExchange("demo.ops.exchange");

        verify(rabbitAdmin).deleteExchange("demo.ops.exchange");
    }

    @Test
    void deleteExchange_withNonManagedNameThatDoesNotExist_throwsNotFoundWithoutCallingDelete() {
        given(rabbitTemplate.execute(any())).willReturn(false);

        assertThatThrownBy(() -> service.deleteExchange("demo.ops.exchange"))
                .isInstanceOf(AdminResourceNotFoundException.class);
        verify(rabbitAdmin, never()).deleteExchange(anyString());
    }

    @Test
    void createBinding_withQueueDestinationThatDoesNotExist_throwsNotFoundBeforeDeclaringBinding() {
        given(rabbitAdmin.getQueueProperties("q.does.not.exist")).willReturn(null);
        BindingRequest request = new BindingRequest("demo.ops.exchange", "q.does.not.exist", "QUEUE", "x");

        assertThatThrownBy(() -> service.createBinding(request)).isInstanceOf(AdminResourceNotFoundException.class);
        verify(rabbitAdmin, never()).declareBinding(any(Binding.class));
    }

    @Test
    void createBinding_withQueueDestinationThatExists_declaresBindingAndEchoesFields() {
        given(rabbitAdmin.getQueueProperties("q.demo.ops")).willReturn(new java.util.Properties());
        BindingRequest request = new BindingRequest("demo.ops.exchange", "q.demo.ops", "QUEUE", "demo.key");

        AdminBindingInfo result = service.createBinding(request);

        assertThat(result).isEqualTo(new AdminBindingInfo("demo.ops.exchange", "q.demo.ops", "QUEUE", "demo.key"));
        verify(rabbitAdmin).declareBinding(any(Binding.class));
    }

    @Test
    void createBinding_withNullRoutingKey_normalizesToEmptyString() {
        given(rabbitAdmin.getQueueProperties("q.demo.ops")).willReturn(new java.util.Properties());
        BindingRequest request = new BindingRequest("demo.ops.exchange", "q.demo.ops", "QUEUE", null);

        AdminBindingInfo result = service.createBinding(request);

        assertThat(result.routingKey()).isEmpty();
    }

    @Test
    void deleteBinding_withNonManagedNames_removesBindingRegardlessOfPriorExistence() {
        BindingRequest request = new BindingRequest("demo.ops.exchange", "q.demo.ops", "QUEUE", "x");

        service.deleteBinding(request);

        verify(rabbitAdmin).removeBinding(any(Binding.class));
    }

    @Test
    void getQueueInfo_withExistingQueue_returnsInfo() {
        given(rabbitAdmin.getQueueInfo("q.demo.ops")).willReturn(new QueueInformation("q.demo.ops", 2, 1));

        AdminQueueInfo result = service.getQueueInfo("q.demo.ops");

        assertThat(result).isEqualTo(new AdminQueueInfo("q.demo.ops", true, false, false, 2, 1));
    }

    @Test
    void getQueueInfo_withMissingQueue_throwsNotFound() {
        given(rabbitAdmin.getQueueInfo(anyString())).willReturn(null);

        assertThatThrownBy(() -> service.getQueueInfo("q.does.not.exist")).isInstanceOf(AdminResourceNotFoundException.class);
    }
}
