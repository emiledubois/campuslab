package cl.campuslab.kafkaadmin.kafka;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.kafka.common.config.TopicConfig;

/**
 * Lista blanca de claves de configuracion de topico (kafka-admin-write.md §3.8). Se valida
 * aqui y no con anotaciones porque un DTO tipado ignoraria en silencio claves desconocidas,
 * que es justamente lo que esta lista debe rechazar.
 */
public final class TopicConfigPolicy {

    public static final long MIN_RETENTION_MS = 3_600_000L;
    public static final long MAX_RETENTION_MS = 2_592_000_000L;
    public static final List<String> ALLOWED_CLEANUP_POLICIES = List.of("delete", "compact", "compact,delete");
    public static final List<String> ALLOWED_KEYS =
            List.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.RETENTION_MS_CONFIG);

    private static final Pattern DECIMAL_INTEGER = Pattern.compile("^[0-9]{1,18}$");

    private TopicConfigPolicy() {
    }

    public static void validate(Map<String, String> configs) {
        if (configs == null) {
            return;
        }
        configs.forEach(TopicConfigPolicy::validateEntry);
    }

    private static void validateEntry(String key, String value) {
        if (TopicConfig.RETENTION_MS_CONFIG.equals(key)) {
            validateRetention(value);
        } else if (TopicConfig.CLEANUP_POLICY_CONFIG.equals(key)) {
            validateCleanupPolicy(value);
        } else {
            throw new InvalidKafkaAdminRequestException("Clave de configuracion no permitida: '" + key
                    + "'. Permitidas: " + String.join(", ", ALLOWED_KEYS) + ".");
        }
    }

    private static void validateRetention(String value) {
        if (value == null || !DECIMAL_INTEGER.matcher(value).matches()) {
            throw invalidRetention(value);
        }
        long retention = Long.parseLong(value);
        if (retention < MIN_RETENTION_MS || retention > MAX_RETENTION_MS) {
            throw invalidRetention(value);
        }
    }

    private static InvalidKafkaAdminRequestException invalidRetention(String value) {
        return new InvalidKafkaAdminRequestException("Valor invalido para '" + TopicConfig.RETENTION_MS_CONFIG
                + "': '" + value + "'. Debe ser un entero entre " + MIN_RETENTION_MS + " y " + MAX_RETENTION_MS + ".");
    }

    private static void validateCleanupPolicy(String value) {
        if (value == null || !ALLOWED_CLEANUP_POLICIES.contains(value)) {
            throw new InvalidKafkaAdminRequestException("Valor invalido para '" + TopicConfig.CLEANUP_POLICY_CONFIG
                    + "': '" + value + "'. Debe ser uno de: " + String.join(" | ", ALLOWED_CLEANUP_POLICIES) + ".");
        }
    }
}
