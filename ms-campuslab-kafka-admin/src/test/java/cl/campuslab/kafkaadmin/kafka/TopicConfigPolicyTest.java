package cl.campuslab.kafkaadmin.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** kafka-admin-write.md §11.2 AC28: limites exactos de la lista blanca de configuracion. */
class TopicConfigPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"3600000", "2592000000", "86400000"})
    void validate_retentionDentroDelRango_seAcepta(String value) {
        // Arrange
        Map<String, String> configs = Map.of("retention.ms", value);

        // Act + Assert
        assertThatCode(() -> TopicConfigPolicy.validate(configs)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"3599999", "2592000001", "-1", "abc", "", "1e9", "+3600000", "99999999999999999999"})
    void validate_retentionFueraDeRangoOMalFormada_seRechazaNombrandoLaClave(String value) {
        // Arrange
        Map<String, String> configs = Map.of("retention.ms", value);

        // Act + Assert
        assertThatThrownBy(() -> TopicConfigPolicy.validate(configs))
                .isInstanceOf(InvalidKafkaAdminRequestException.class)
                .hasMessageContaining("'retention.ms'")
                .hasMessageContaining("3600000 y 2592000000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "compact", "compact,delete"})
    void validate_cleanupPolicyPermitida_seAcepta(String value) {
        // Arrange
        Map<String, String> configs = Map.of("cleanup.policy", value);

        // Act + Assert
        assertThatCode(() -> TopicConfigPolicy.validate(configs)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"borrar", "delete,compact", "DELETE", "", " delete"})
    void validate_cleanupPolicyNoPermitida_seRechaza(String value) {
        // Arrange
        Map<String, String> configs = Map.of("cleanup.policy", value);

        // Act + Assert
        assertThatThrownBy(() -> TopicConfigPolicy.validate(configs))
                .isInstanceOf(InvalidKafkaAdminRequestException.class)
                .hasMessageContaining("'cleanup.policy'");
    }

    @Test
    void validate_claveDesconocida_seRechazaListandoLasPermitidas() {
        // Arrange
        Map<String, String> configs = Map.of("segment.ms", "1000");

        // Act + Assert
        assertThatThrownBy(() -> TopicConfigPolicy.validate(configs))
                .isInstanceOf(InvalidKafkaAdminRequestException.class)
                .hasMessage("Clave de configuracion no permitida: 'segment.ms'. Permitidas: cleanup.policy, retention.ms.");
    }

    @Test
    void validate_valorNulo_seRechaza() {
        // Arrange
        Map<String, String> configs = new HashMap<>();
        configs.put("retention.ms", null);

        // Act + Assert
        assertThatThrownBy(() -> TopicConfigPolicy.validate(configs))
                .isInstanceOf(InvalidKafkaAdminRequestException.class)
                .hasMessageContaining("'retention.ms'");
    }

    @Test
    void validate_mapaNulo_noHaceNada() {
        // Act + Assert
        assertThatCode(() -> TopicConfigPolicy.validate(null)).doesNotThrowAnyException();
    }
}
