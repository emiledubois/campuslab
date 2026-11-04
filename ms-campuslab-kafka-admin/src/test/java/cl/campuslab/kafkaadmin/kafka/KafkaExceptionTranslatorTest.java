package cl.campuslab.kafkaadmin.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.GroupNotEmptyException;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.InvalidPartitionsException;
import org.apache.kafka.common.errors.InvalidReplicationFactorException;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.PolicyViolationException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownMemberIdException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** kafka-admin-write.md §11.2 AC27: cada fila de la tabla §6.3. */
class KafkaExceptionTranslatorTest {

    @AfterEach
    void limpiarFlagDeInterrupcion() {
        Thread.interrupted();
    }

    static Stream<Arguments> filasDeLaTabla() {
        return Stream.of(
                Arguments.of(new TopicExistsException("x"), AdminResourceConflictException.class, "ya existe"),
                Arguments.of(new UnknownTopicOrPartitionException("x"), AdminResourceNotFoundException.class, "No existe un topico"),
                Arguments.of(new GroupIdNotFoundException("x"), AdminResourceNotFoundException.class, "No existe un consumer group"),
                Arguments.of(new GroupNotEmptyException("x"), AdminResourceConflictException.class, "tiene miembros activos"),
                Arguments.of(new UnknownMemberIdException("x"), AdminResourceConflictException.class, "tiene miembros activos"),
                Arguments.of(new InvalidPartitionsException("mensaje broker"), InvalidKafkaAdminRequestException.class, "mensaje broker"),
                Arguments.of(new InvalidReplicationFactorException("mensaje broker"), InvalidKafkaAdminRequestException.class, "mensaje broker"),
                Arguments.of(new InvalidConfigurationException("mensaje broker"), InvalidKafkaAdminRequestException.class, "mensaje broker"),
                Arguments.of(new InvalidTopicException("mensaje broker"), InvalidKafkaAdminRequestException.class, "mensaje broker"),
                Arguments.of(new InvalidRequestException("mensaje broker"), InvalidKafkaAdminRequestException.class, "mensaje broker"),
                Arguments.of(new PolicyViolationException("mensaje broker"), InvalidKafkaAdminRequestException.class, "mensaje broker"),
                Arguments.of(new org.apache.kafka.common.errors.TimeoutException("t"), KafkaUnavailableException.class, "Kafka is currently unreachable."),
                Arguments.of(new java.util.concurrent.TimeoutException("t"), KafkaUnavailableException.class, "Kafka is currently unreachable."),
                Arguments.of(new IllegalStateException("otra"), KafkaUnavailableException.class, "Kafka is currently unreachable."));
    }

    @ParameterizedTest
    @MethodSource("filasDeLaTabla")
    void translate_causaEnvueltaEnExecutionException_produceExcepcionDeDominioEsperada(
            Throwable cause, Class<? extends RuntimeException> expected, String messageFragment) {
        // Arrange
        ExecutionException wrapped = new ExecutionException(cause);

        // Act
        RuntimeException result = KafkaExceptionTranslator.translate(wrapped, "topico", "demo.ops.topic");

        // Assert
        assertThat(result).isInstanceOf(expected);
        assertThat(result.getMessage()).contains(messageFragment);
    }

    @Test
    void translate_causaEnvueltaEnCompletionException_seDesenvuelve() {
        // Arrange
        CompletionException wrapped = new CompletionException(new TopicExistsException("x"));

        // Act
        RuntimeException result = KafkaExceptionTranslator.translate(wrapped, "topico", "demo.ops.topic");

        // Assert
        assertThat(result).isInstanceOf(AdminResourceConflictException.class)
                .hasMessage("El topico 'demo.ops.topic' ya existe.");
    }

    @Test
    void translate_interruptedException_restauraElFlagDeInterrupcion() {
        // Arrange
        InterruptedException interrupted = new InterruptedException();

        // Act
        RuntimeException result = KafkaExceptionTranslator.translate(interrupted, "topico", "demo.ops.topic");

        // Assert
        assertThat(result).isInstanceOf(KafkaUnavailableException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void translate_grupoNoVacioConAccion_incluyeLaAccionEnElMensaje() {
        // Arrange
        ExecutionException wrapped = new ExecutionException(new GroupNotEmptyException("x"));

        // Act
        RuntimeException result = KafkaExceptionTranslator.translate(wrapped, "consumer group", "g", "eliminarlo");

        // Assert
        assertThat(result).hasMessage(
                "El consumer group 'g' tiene miembros activos; detenga sus consumidores antes de eliminarlo.");
    }
}
