package cl.campuslab.kafkaadmin.kafka;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
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

/**
 * Traduce fallas de {@code AdminClient} a excepciones de dominio (kafka-admin-write.md §6.3),
 * para que el controller nunca vea tipos de {@code org.apache.kafka.*}. Todo lo que no es un
 * error de negocio reconocido se trata como Kafka no disponible (503), nunca como 500.
 */
public final class KafkaExceptionTranslator {

    static final String DEFAULT_GROUP_ACTION = "operar sobre el";

    private KafkaExceptionTranslator() {
    }

    public static RuntimeException translate(Throwable failure, String resourceType, String name) {
        return translate(failure, resourceType, name, DEFAULT_GROUP_ACTION);
    }

    /**
     * {@code groupAction} solo completa el mensaje de "miembros activos" (por ejemplo
     * "eliminarlo" o "reiniciar sus offsets"); el mapeo de tipos es el mismo.
     */
    public static RuntimeException translate(Throwable failure, String resourceType, String name, String groupAction) {
        Throwable cause = unwrap(failure);

        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new KafkaUnavailableException(cause);
        }
        if (cause instanceof TopicExistsException) {
            return new AdminResourceConflictException("El topico '" + name + "' ya existe.");
        }
        if (cause instanceof UnknownTopicOrPartitionException) {
            return new AdminResourceNotFoundException("topico", name);
        }
        if (cause instanceof GroupIdNotFoundException) {
            return new AdminResourceNotFoundException("consumer group", name);
        }
        if (cause instanceof GroupNotEmptyException || cause instanceof UnknownMemberIdException) {
            return activeMembersConflict(name, groupAction);
        }
        if (cause instanceof InvalidPartitionsException
                || cause instanceof InvalidReplicationFactorException
                || cause instanceof InvalidConfigurationException
                || cause instanceof InvalidTopicException
                || cause instanceof InvalidRequestException
                || cause instanceof PolicyViolationException) {
            return new InvalidKafkaAdminRequestException(cause.getMessage());
        }
        return new KafkaUnavailableException(cause);
    }

    static AdminResourceConflictException activeMembersConflict(String groupId, String groupAction) {
        return new AdminResourceConflictException("El consumer group '" + groupId
                + "' tiene miembros activos; detenga sus consumidores antes de " + groupAction + ".");
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
