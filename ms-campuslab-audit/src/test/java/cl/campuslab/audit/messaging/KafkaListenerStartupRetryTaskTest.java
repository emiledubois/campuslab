package cl.campuslab.audit.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * docs/designs/aws-deployment.md Part 9 / AC22 - proves {@code
 * KafkaListenerStartupRetryTask}'s retry/reset logic in isolation, in particular the
 * {@code isRunning()} landmine workaround (a failed {@code start()} leaves the outer
 * container reporting {@code isRunning()==true} with nothing actually running, so the
 * catch block must call {@code stop()} to reset the flag for the next tick).
 */
@ExtendWith(MockitoExtension.class)
class KafkaListenerStartupRetryTaskTest {

    @Mock
    private KafkaListenerEndpointRegistry registry;

    @Mock
    private MessageListenerContainer notRunningContainer;

    @Mock
    private MessageListenerContainer alreadyRunningContainer;

    private KafkaListenerStartupRetryTask task;

    @BeforeEach
    void setUp() {
        task = new KafkaListenerStartupRetryTask(registry);
    }

    @Test
    void ensureListenersStarted_whenContainerNotRunning_callsStart() {
        given(registry.getListenerContainers()).willReturn(List.of(notRunningContainer));
        given(notRunningContainer.isRunning()).willReturn(false);

        task.ensureListenersStarted();

        verify(notRunningContainer).start();
    }

    @Test
    void ensureListenersStarted_whenContainerAlreadyRunning_neverCallsStart() {
        given(registry.getListenerContainers()).willReturn(List.of(alreadyRunningContainer));
        given(alreadyRunningContainer.isRunning()).willReturn(true);

        task.ensureListenersStarted();

        verify(alreadyRunningContainer, never()).start();
    }

    @Test
    void ensureListenersStarted_whenStartThrows_swallowsExceptionAndStopsContainerToResetFlag() {
        given(registry.getListenerContainers()).willReturn(List.of(notRunningContainer));
        given(notRunningContainer.isRunning()).willReturn(false);
        willThrow(new RuntimeException("broker unreachable")).given(notRunningContainer).start();

        assertThatCode(() -> task.ensureListenersStarted()).doesNotThrowAnyException();

        verify(notRunningContainer).stop();
    }
}
