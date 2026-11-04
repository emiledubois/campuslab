package cl.campuslab.kafkaadmin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.campuslab.kafkaadmin.AbstractIntegrationTest;
import cl.campuslab.kafkaadmin.kafka.AdminResourceConflictException;
import cl.campuslab.kafkaadmin.kafka.KafkaExceptionTranslator;
import cl.campuslab.kafkaadmin.topology.KafkaTopology;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.UnknownMemberIdException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * kafka-admin-write.md §11.1 (AC1-AC24) contra un broker Kafka real (Testcontainers). La
 * validacion JWT esta mockeada aqui (probada por EntraJwtValidationTest/
 * SecurityIntegrationTest); lo que se prueba es el comportamiento real del broker, incluida
 * la excepcion exacta de {@code alterConsumerGroupOffsets} con miembros activos (open
 * question 4 del diseno).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class KafkaAdminApiTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/admin/kafka";
    private static final String AUTH = "Bearer admin-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Admin adminClient;

    @MockBean
    private JwtDecoder jwtDecoder;

    private final List<String> topicsToCleanUp = new ArrayList<>();
    private final List<String> groupsToCleanUp = new ArrayList<>();
    private final List<KafkaConsumer<String, String>> openConsumers = new ArrayList<>();

    @BeforeEach
    void stubAdminToken() {
        given(jwtDecoder.decode("admin-token")).willReturn(adminJwt());
    }

    @AfterEach
    void cleanUp() throws Exception {
        openConsumers.forEach(KafkaConsumer::close);
        openConsumers.clear();
        Set<String> existingGroups = adminClient.listConsumerGroups().all().get(5, TimeUnit.SECONDS).stream()
                .map(ConsumerGroupListing::groupId).collect(java.util.stream.Collectors.toSet());
        List<String> groups = groupsToCleanUp.stream().filter(existingGroups::contains).toList();
        if (!groups.isEmpty()) {
            adminClient.deleteConsumerGroups(groups).all().get(10, TimeUnit.SECONDS);
        }
        Set<String> existingTopics = topicNames();
        List<String> topics = topicsToCleanUp.stream().filter(existingTopics::contains).toList();
        if (!topics.isEmpty()) {
            adminClient.deleteTopics(topics).all().get(10, TimeUnit.SECONDS);
        }
    }

    // --- AC1/AC2: crear topico y duplicado ---

    @Test
    void createTopic_conConfig_creaElTopicoConRfPorDefectoYRetencion() throws Exception {
        // Arrange
        topicsToCleanUp.add("demo.ops.topic");

        // Act
        perform(post(BASE + "/topics"),
                "{\"name\":\"demo.ops.topic\",\"partitions\":3,\"configs\":{\"retention.ms\":\"86400000\"}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("demo.ops.topic"))
                .andExpect(jsonPath("$.partitions").value(3))
                .andExpect(jsonPath("$.replicationFactor").value(1))
                .andExpect(jsonPath("$.configs['retention.ms']").value("86400000"));

        // Assert
        assertThat(topicNames()).contains("demo.ops.topic");
        assertThat(configValue("demo.ops.topic", "retention.ms")).isEqualTo("86400000");
    }

    @Test
    void createTopic_duplicado_retorna409YaExiste() throws Exception {
        // Arrange
        topicsToCleanUp.add("demo.dup.topic");
        String body = "{\"name\":\"demo.dup.topic\",\"partitions\":1}";
        perform(post(BASE + "/topics"), body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.configs").isEmpty());

        // Act + Assert
        perform(post(BASE + "/topics"), body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("El topico 'demo.dup.topic' ya existe."));
    }

    // --- AC3/AC4/AC5: modificar configuracion ---

    @Test
    void updateTopicConfig_retencionYPolitica_seAplicanEnElBroker() throws Exception {
        // Arrange
        createTopicDirect("demo.cfg.topic", 1);

        // Act
        perform(patch(BASE + "/topics/demo.cfg.topic/config"), "{\"configs\":{\"retention.ms\":\"172800000\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("demo.cfg.topic"))
                .andExpect(jsonPath("$.configs['retention.ms']").value("172800000"));
        perform(patch(BASE + "/topics/demo.cfg.topic/config"), "{\"configs\":{\"cleanup.policy\":\"compact,delete\"}}")
                .andExpect(status().isOk());

        // Assert
        assertThat(configValue("demo.cfg.topic", "retention.ms")).isEqualTo("172800000");
        assertThat(configValue("demo.cfg.topic", "cleanup.policy")).isEqualTo("compact,delete");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"configs\":{\"segment.ms\":\"1000\"}}|segment.ms",
            "{\"configs\":{\"retention.ms\":\"abc\"}}|retention.ms",
            "{\"configs\":{\"retention.ms\":\"1000\"}}|retention.ms",
            "{\"configs\":{\"cleanup.policy\":\"borrar\"}}|cleanup.policy",
            "{\"configs\":{}}|configs: debe incluir al menos una clave"})
    void updateTopicConfig_configuracionInvalida_retorna400NombrandoLaClave(String row) throws Exception {
        // Arrange
        String[] parts = row.split("\\|");

        // Act + Assert
        perform(patch(BASE + "/topics/demo.any.topic/config"), parts[0])
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value(containsString(parts[1])))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void updateTopicConfig_topicoInexistente_retorna404() throws Exception {
        // Act + Assert
        perform(patch(BASE + "/topics/no.existe.topic/config"), "{\"configs\":{\"retention.ms\":\"86400000\"}}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No existe un topico llamado 'no.existe.topic'."));
    }

    // --- AC6/AC7/AC8: particiones ---

    @Test
    void increasePartitions_de3a6_ySiguienteReduccionOIgualdadRetornan400() throws Exception {
        // Arrange
        createTopicDirect("demo.part.topic", 3);

        // Act
        perform(post(BASE + "/topics/demo.part.topic/partitions"), "{\"totalCount\":6}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("demo.part.topic"))
                .andExpect(jsonPath("$.previousPartitions").value(3))
                .andExpect(jsonPath("$.partitions").value(6));

        // Assert
        assertThat(partitionCount("demo.part.topic")).isEqualTo(6);
        perform(post(BASE + "/topics/demo.part.topic/partitions"), "{\"totalCount\":2}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("debe ser mayor que la actual (6)")));
        perform(post(BASE + "/topics/demo.part.topic/partitions"), "{\"totalCount\":6}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("debe ser mayor que la actual (6)")));
    }

    @Test
    void increasePartitions_topicoInexistente_retorna404() throws Exception {
        // Act + Assert
        perform(post(BASE + "/topics/no.existe.topic/partitions"), "{\"totalCount\":4}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No existe un topico llamado 'no.existe.topic'."));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/topics|{\"name\":\"demo.rango.topic\",\"partitions\":0}|partitions: debe ser al menos 1",
            "/topics|{\"name\":\"demo.rango.topic\",\"partitions\":13}|partitions: debe ser como maximo 12",
            "/topics|{\"name\":\"demo.rango.topic\"}|partitions: es obligatorio",
            "/topics/demo.rango.topic/partitions|{\"totalCount\":13}|totalCount: debe ser como maximo 12",
            "/topics/demo.rango.topic/partitions|{\"totalCount\":1}|totalCount: debe ser al menos 2",
            "/topics/demo.rango.topic/partitions|{}|totalCount: es obligatorio",
            "/topics|{\"name\":\"demo.rango.topic\",\"partitions\":1,\"replicationFactor\":4}|replicationFactor: debe ser como maximo 3",
            "/topics|{\"name\":\"demo.rango.topic\",\"partitions\":1,\"replicationFactor\":0}|replicationFactor: debe ser al menos 1"})
    void rangos_fueraDeLimite_retornan400ConMensajeDeCampo(String row) throws Exception {
        // Arrange
        String[] parts = row.split("\\|");

        // Act + Assert
        perform(post(BASE + parts[0]), parts[1])
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString(parts[2])));
        assertThat(topicNames()).doesNotContain("demo.rango.topic");
    }

    // --- AC9: RF mayor al cluster ---

    @Test
    void createTopic_conRfMayorQueBrokers_retorna400ConCantidadReal() throws Exception {
        // Act + Assert
        perform(post(BASE + "/topics"), "{\"name\":\"demo.rf.topic\",\"partitions\":1,\"replicationFactor\":2}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("excede la cantidad de brokers disponibles (1)")));
        assertThat(topicNames()).doesNotContain("demo.rf.topic");
    }

    // --- AC10: eliminar y segundo DELETE ---

    @Test
    void deleteTopic_borraYSegundoDeleteRetorna404() throws Exception {
        // Arrange
        createTopicDirect("demo.del.topic", 1);

        // Act
        perform(delete(BASE + "/topics/demo.del.topic"), null).andExpect(status().isNoContent());

        // Assert
        assertThat(topicNames()).doesNotContain("demo.del.topic");
        perform(delete(BASE + "/topics/demo.del.topic"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("No existe un topico llamado 'demo.del.topic'."));
    }

    // --- AC11/AC12/AC13: nombres y JSON ---

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void createTopic_nombreVacio_retorna400(String name) throws Exception {
        // Act + Assert
        perform(post(BASE + "/topics"), "{\"name\":\"" + name + "\",\"partitions\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("name: no debe estar vacio")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a/b", "con espacio", ".", "..", "nombre#raro", "LARGO250"})
    void createTopic_nombreInvalido_retorna400ConMensajeDePatron(String name) throws Exception {
        // Arrange
        String actualName = "LARGO250".equals(name) ? "a".repeat(250) : name;

        // Act + Assert
        perform(post(BASE + "/topics"), "{\"name\":\"" + actualName + "\",\"partitions\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString(
                        "name: debe tener entre 1 y 249 caracteres de [a-zA-Z0-9._-] y no puede ser '.' ni '..'")));
    }

    @Test
    void createTopic_nombreDe249Caracteres_retorna201() throws Exception {
        // Arrange
        String name = "b".repeat(249);
        topicsToCleanUp.add(name);

        // Act + Assert
        perform(post(BASE + "/topics"), "{\"name\":\"" + name + "\",\"partitions\":1}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name));
        assertThat(topicNames()).contains(name);
    }

    @Test
    void createTopic_jsonMalformado_retorna400SinStackTrace() throws Exception {
        // Act + Assert
        perform(post(BASE + "/topics"), "{no-json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El cuerpo de la solicitud falta o no es JSON valido."))
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    // --- AC14-AC18: topologia gestionada protegida ---

    @Test
    void deleteTopic_bookingsEvents_retorna409YElTopicoSigueIntacto() throws Exception {
        // Act + Assert
        perform(delete(BASE + "/topics/bookings.events"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("bookings.events")))
                .andExpect(jsonPath("$.detail").value(containsString("topologia base de Kafka")));
        assertThat(topicNames()).contains("bookings.events");
        assertThat(partitionCount("bookings.events")).isEqualTo(3);
    }

    @Test
    void updateTopicConfig_auditTimeline_retorna409YLaConfigNoCambia() throws Exception {
        // Act + Assert
        perform(patch(BASE + "/topics/audit.timeline/config"), "{\"configs\":{\"retention.ms\":\"86400000\"}}")
                .andExpect(status().isConflict());
        assertThat(configValue("audit.timeline", "retention.ms")).isEqualTo("1814400000");
        assertThat(configValue("audit.timeline", "cleanup.policy")).isEqualTo("compact,delete");
    }

    @Test
    void dlts_protegidasContraDeletePatchYParticiones() throws Exception {
        // Act + Assert
        perform(delete(BASE + "/topics/bookings.events.audit-service.DLT"), null)
                .andExpect(status().isConflict());
        perform(patch(BASE + "/topics/audit.timeline.report-timeline-projection.DLT/config"),
                "{\"configs\":{\"retention.ms\":\"86400000\"}}")
                .andExpect(status().isConflict());
        perform(post(BASE + "/topics/bookings.events.report-service.DLT/partitions"), "{\"totalCount\":4}")
                .andExpect(status().isConflict());
        assertThat(topicNames()).containsAll(KafkaTopology.DLT_NAMES);
        assertThat(partitionCount("bookings.events.report-service.DLT")).isEqualTo(3);
        assertThat(configValue("audit.timeline.report-timeline-projection.DLT", "retention.ms")).isEqualTo("864000000");
    }

    @Test
    void createTopic_nombreGestionadoOColisionante_retorna409DeProteccion() throws Exception {
        // Act + Assert
        perform(post(BASE + "/topics"), "{\"name\":\"bookings.events\",\"partitions\":3}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("topologia base de Kafka")));
        perform(post(BASE + "/topics"), "{\"name\":\"bookings_events\",\"partitions\":3}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("colisiona con el topico gestionado 'bookings.events'")));
        assertThat(topicNames()).doesNotContain("bookings_events");
    }

    @Test
    void getTopics_sigueListandoSoloLosCincoGestionadosConConfigCorrecta() throws Exception {
        // Arrange
        createTopicDirect("demo.extra.topic", 1);

        // Act + Assert
        perform(get(BASE + "/topics"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[?(@.name == 'demo.extra.topic')]").isEmpty())
                .andExpect(jsonPath("$[?(@.configMatches == false)]").isEmpty());
    }

    // --- AC19: reset reflejado en lag ---

    @Test
    void resetOffsets_earliestYLatest_seReflejanEnElLag() throws Exception {
        // Arrange
        topicsToCleanUp.add("demo.reset.topic");
        groupsToCleanUp.add("demo.ops.group");
        perform(post(BASE + "/topics"), "{\"name\":\"demo.reset.topic\",\"partitions\":1}")
                .andExpect(status().isCreated());
        produce("demo.reset.topic", 5);
        consumeAllAndCommit("demo.reset.topic", "demo.ops.group", 5);
        perform(get(BASE + "/consumer-groups"), null)
                .andExpect(jsonPath("$[?(@.groupId == 'demo.ops.group')].topics[0].totalLag", contains(0)));

        // Act
        perform(post(BASE + "/consumer-groups/demo.ops.group/reset-offsets"),
                "{\"topic\":\"demo.reset.topic\",\"strategy\":\"EARLIEST\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value("demo.ops.group"))
                .andExpect(jsonPath("$.topic").value("demo.reset.topic"))
                .andExpect(jsonPath("$.strategy").value("EARLIEST"))
                .andExpect(jsonPath("$.partitions[0].partition").value(0))
                .andExpect(jsonPath("$.partitions[0].offset").value(0));

        // Assert
        perform(get(BASE + "/consumer-groups"), null)
                .andExpect(jsonPath("$[?(@.groupId == 'demo.ops.group')].topics[0].totalLag", contains(5)));
        perform(post(BASE + "/consumer-groups/demo.ops.group/reset-offsets"),
                "{\"topic\":\"demo.reset.topic\",\"strategy\":\"LATEST\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partitions[0].offset").value(5));
        perform(get(BASE + "/consumer-groups"), null)
                .andExpect(jsonPath("$[?(@.groupId == 'demo.ops.group')].topics[0].totalLag", contains(0)));
    }

    // --- AC20/AC21: grupo con miembros activos ---

    @Test
    void resetYDelete_conMiembrosActivos_retornan409YLosOffsetsNoCambian() throws Exception {
        // Arrange
        createTopicDirect("demo.active.topic", 1);
        groupsToCleanUp.add("demo.active.group");
        produce("demo.active.topic", 3);
        KafkaConsumer<String, String> active = subscribeAndJoin("demo.active.topic", "demo.active.group");
        active.commitSync(Map.of(new TopicPartition("demo.active.topic", 0), new OffsetAndMetadata(3)));

        // Act + Assert
        perform(post(BASE + "/consumer-groups/demo.active.group/reset-offsets"),
                "{\"topic\":\"demo.active.topic\",\"strategy\":\"EARLIEST\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("tiene miembros activos")));
        perform(delete(BASE + "/consumer-groups/demo.active.group"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "El consumer group 'demo.active.group' tiene miembros activos; "
                                + "detenga sus consumidores antes de eliminarlo."));
        assertThat(committedOffset("demo.active.group", new TopicPartition("demo.active.topic", 0))).isEqualTo(3L);
    }

    /**
     * Open question 4 del diseno, confirmado contra cp-kafka 7.9.9 con kafka-clients 3.9.2 y
     * no asumido: saltando el pre-chequeo de miembros, {@code alterConsumerGroupOffsets} sobre
     * un grupo con un miembro activo falla con {@link UnknownMemberIdException}, y el
     * traductor lo mapea a 409.
     */
    @Test
    void alterConsumerGroupOffsets_directoConMiembroActivo_brokerLanzaUnknownMemberIdYSeTraduceA409() throws Exception {
        // Arrange
        createTopicDirect("demo.probe.topic", 1);
        groupsToCleanUp.add("demo.probe.group");
        subscribeAndJoin("demo.probe.topic", "demo.probe.group");
        TopicPartition partition = new TopicPartition("demo.probe.topic", 0);

        // Act
        Throwable failure = catchThrowable(() -> adminClient
                .alterConsumerGroupOffsets("demo.probe.group", Map.of(partition, new OffsetAndMetadata(0)))
                .all().get(10, TimeUnit.SECONDS));

        // Assert
        assertThat(failure).isInstanceOf(ExecutionException.class);
        assertThat(failure.getCause()).isInstanceOf(UnknownMemberIdException.class);
        assertThat(KafkaExceptionTranslator.translate(failure, "consumer group", "demo.probe.group"))
                .isInstanceOf(AdminResourceConflictException.class)
                .hasMessageContaining("tiene miembros activos");
    }

    // --- AC22: borrar grupo inactivo y segundo DELETE ---

    @Test
    void deleteConsumerGroup_inactivo_borraYSegundoDeleteRetorna404() throws Exception {
        // Arrange
        createTopicDirect("demo.grp.topic", 1);
        groupsToCleanUp.add("demo.grp.group");
        commitWithoutMembership("demo.grp.topic", "demo.grp.group", 0);

        // Act
        perform(delete(BASE + "/consumer-groups/demo.grp.group"), null).andExpect(status().isNoContent());

        // Assert
        perform(get(BASE + "/consumer-groups"), null)
                .andExpect(jsonPath("$[?(@.groupId == 'demo.grp.group')]").isEmpty());
        perform(delete(BASE + "/consumer-groups/demo.grp.group"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No existe un consumer group llamado 'demo.grp.group'."));
    }

    // --- AC23: inexistentes y validacion en reset ---

    @Test
    void resetOffsets_grupoOTopicoInexistente_retorna404() throws Exception {
        // Arrange
        createTopicDirect("demo.nf.topic", 1);
        groupsToCleanUp.add("demo.nf.group");
        commitWithoutMembership("demo.nf.topic", "demo.nf.group", 0);

        // Act + Assert
        perform(post(BASE + "/consumer-groups/no.existe/reset-offsets"),
                "{\"topic\":\"demo.nf.topic\",\"strategy\":\"EARLIEST\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No existe un consumer group llamado 'no.existe'."));
        perform(post(BASE + "/consumer-groups/demo.nf.group/reset-offsets"),
                "{\"topic\":\"no.existe.topic\",\"strategy\":\"EARLIEST\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No existe un topico llamado 'no.existe.topic'."));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"topic\":\"demo.nf.topic\",\"strategy\":\"MIDDLE\"}|strategy: debe ser EARLIEST o LATEST",
            "{\"topic\":\"\",\"strategy\":\"EARLIEST\"}|topic: no debe estar vacio",
            "{\"topic\":\"demo.nf.topic\"}|strategy: no debe estar vacio"})
    void resetOffsets_cuerpoInvalido_retorna400(String row) throws Exception {
        // Arrange
        String[] parts = row.split("\\|");

        // Act + Assert
        perform(post(BASE + "/consumer-groups/demo.nf.group/reset-offsets"), parts[0])
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString(parts[1])));
    }

    @Test
    void deleteConsumerGroup_inexistente_retorna404() throws Exception {
        // Act + Assert
        perform(delete(BASE + "/consumer-groups/no.existe.group"), null)
                .andExpect(status().isNotFound());
    }

    // --- AC24: grupos del sistema ---

    @Test
    void gruposDelSistema_deleteYLatestProtegidos_earliestPermitido() throws Exception {
        // Arrange
        groupsToCleanUp.add("audit-service");
        commitWithoutMembership("bookings.events", "audit-service", 0);

        // Act + Assert
        perform(delete(BASE + "/consumer-groups/audit-service"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("audit-service")));
        perform(post(BASE + "/consumer-groups/report-service/reset-offsets"),
                "{\"topic\":\"bookings.events\",\"strategy\":\"LATEST\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("solo se permite EARLIEST")));
        perform(post(BASE + "/consumer-groups/audit-service/reset-offsets"),
                "{\"topic\":\"bookings.events\",\"strategy\":\"EARLIEST\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partitions", hasSize(3)));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", AUTH);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private void createTopicDirect(String name, int partitions) throws Exception {
        topicsToCleanUp.add(name);
        adminClient.createTopics(List.of(new NewTopic(name, partitions, (short) 1))).all().get(10, TimeUnit.SECONDS);
    }

    private Set<String> topicNames() throws Exception {
        return adminClient.listTopics().names().get(5, TimeUnit.SECONDS);
    }

    private int partitionCount(String topic) throws Exception {
        return adminClient.describeTopics(List.of(topic)).topicNameValues().get(topic)
                .get(5, TimeUnit.SECONDS).partitions().size();
    }

    private String configValue(String topic, String key) throws Exception {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        Config config = adminClient.describeConfigs(List.of(resource)).values().get(resource).get(5, TimeUnit.SECONDS);
        return config.get(key).value();
    }

    private long committedOffset(String groupId, TopicPartition partition) throws Exception {
        return adminClient.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS).get(partition).offset();
    }

    private void produce(String topic, int count) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (int i = 0; i < count; i++) {
                producer.send(new ProducerRecord<>(topic, "k" + i, "v" + i));
            }
            producer.flush();
        }
    }

    private KafkaConsumer<String, String> newConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    private void consumeAllAndCommit(String topic, String groupId, int expected) {
        try (KafkaConsumer<String, String> consumer = newConsumer(groupId)) {
            consumer.subscribe(List.of(topic));
            int received = 0;
            Instant deadline = Instant.now().plusSeconds(30);
            while (received < expected && Instant.now().isBefore(deadline)) {
                received += consumer.poll(Duration.ofMillis(500)).count();
            }
            assertThat(received).isEqualTo(expected);
            consumer.commitSync();
        }
    }

    private KafkaConsumer<String, String> subscribeAndJoin(String topic, String groupId) {
        KafkaConsumer<String, String> consumer = newConsumer(groupId);
        openConsumers.add(consumer);
        consumer.subscribe(List.of(topic));
        Instant deadline = Instant.now().plusSeconds(30);
        while (consumer.assignment().isEmpty() && Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(500));
        }
        assertThat(consumer.assignment()).isNotEmpty();
        return consumer;
    }

    private void commitWithoutMembership(String topic, String groupId, long offset) {
        try (KafkaConsumer<String, String> consumer = newConsumer(groupId)) {
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.commitSync(Map.of(partition, new OffsetAndMetadata(offset)));
        }
    }

    private static Jwt adminJwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("admin-uuid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("oid", "admin-uuid-oid")
                .claim("iss", "https://login.microsoftonline.com/test-tenant/v2.0")
                .claim("roles", List.of("ADMIN"))
                .build();
    }
}
