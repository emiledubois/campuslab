package cl.campuslab.kafkaadmin.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import cl.campuslab.kafkaadmin.topology.KafkaAdminProperties;
import cl.campuslab.kafkaadmin.topology.KafkaTopology;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.AlterConfigsResult;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DeleteConsumerGroupsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupsResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.GroupNotEmptyException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * kafka-admin-write.md §11.2 AC26: guardas y orden de operaciones con {@code Admin}
 * mockeado. El comportamiento contra un broker real lo prueba {@code KafkaAdminApiTest}.
 */
@ExtendWith(MockitoExtension.class)
class KafkaAdminServiceTest {

    @Mock
    private Admin adminClient;

    @Mock
    private KafkaReachabilityChecker reachabilityChecker;

    private KafkaAdminService service;

    @BeforeEach
    void setUp() {
        KafkaAdminProperties properties =
                new KafkaAdminProperties("localhost:9092", new KafkaAdminProperties.Topic((short) 1));
        service = new KafkaAdminService(adminClient, properties, reachabilityChecker);
    }

    static Stream<String> managedTopics() {
        return KafkaTopology.ALL_TOPICS.stream().map(KafkaTopology.TopicSpec::name);
    }

    static Stream<String> managedGroups() {
        return KafkaTopology.MANAGED_CONSUMER_GROUPS.stream();
    }

    // --- guardas de topologia: sin ninguna interaccion con Kafka ---

    @ParameterizedTest
    @MethodSource("managedTopics")
    void createTopic_conTopicoGestionado_rechazaSinTocarKafka(String managed) {
        // Arrange
        CreateTopicRequest request = new CreateTopicRequest(managed, 3, null, null);

        // Act + Assert
        assertThatThrownBy(() -> service.createTopic(request))
                .isInstanceOf(ProtectedTopologyResourceException.class)
                .hasMessageContaining(managed);
        verifyNoInteractions(adminClient, reachabilityChecker);
    }

    @ParameterizedTest
    @MethodSource("managedTopics")
    void deleteTopic_conTopicoGestionado_rechazaSinTocarKafka(String managed) {
        // Act + Assert
        assertThatThrownBy(() -> service.deleteTopic(managed))
                .isInstanceOf(ProtectedTopologyResourceException.class)
                .hasMessageContaining(managed);
        verifyNoInteractions(adminClient, reachabilityChecker);
    }

    @ParameterizedTest
    @MethodSource("managedTopics")
    void updateTopicConfig_conTopicoGestionado_rechazaSinTocarKafka(String managed) {
        // Arrange
        UpdateTopicConfigRequest request = new UpdateTopicConfigRequest(Map.of("retention.ms", "86400000"));

        // Act + Assert
        assertThatThrownBy(() -> service.updateTopicConfig(managed, request))
                .isInstanceOf(ProtectedTopologyResourceException.class)
                .hasMessageContaining(managed);
        verifyNoInteractions(adminClient, reachabilityChecker);
    }

    @ParameterizedTest
    @MethodSource("managedTopics")
    void increasePartitions_conTopicoGestionado_rechazaSinTocarKafka(String managed) {
        // Arrange
        IncreasePartitionsRequest request = new IncreasePartitionsRequest(6);

        // Act + Assert
        assertThatThrownBy(() -> service.increasePartitions(managed, request))
                .isInstanceOf(ProtectedTopologyResourceException.class)
                .hasMessageContaining(managed);
        verifyNoInteractions(adminClient, reachabilityChecker);
    }

    @Test
    void createTopic_conNombreQueColisionaPorPuntoGuionBajo_rechazaSinTocarKafka() {
        // Arrange
        CreateTopicRequest request = new CreateTopicRequest("bookings_events", 3, null, null);

        // Act + Assert
        assertThatThrownBy(() -> service.createTopic(request))
                .isInstanceOf(ProtectedTopologyResourceException.class)
                .hasMessageContaining("colisiona con el topico gestionado 'bookings.events'");
        verifyNoInteractions(adminClient, reachabilityChecker);
    }

    @ParameterizedTest
    @MethodSource("managedGroups")
    void deleteConsumerGroup_conGrupoDelSistema_rechazaSinTocarKafka(String group) {
        // Act + Assert
        assertThatThrownBy(() -> service.deleteConsumerGroup(group))
                .isInstanceOf(ProtectedTopologyResourceException.class)
                .hasMessageContaining(group);
        verifyNoInteractions(adminClient, reachabilityChecker);
    }

    @ParameterizedTest
    @MethodSource("managedGroups")
    void resetOffsets_latestSobreGrupoDelSistema_rechazaSinTocarKafka(String group) {
        // Arrange
        ResetOffsetsRequest request = new ResetOffsetsRequest("bookings.events", "LATEST");

        // Act + Assert
        assertThatThrownBy(() -> service.resetOffsets(group, request))
                .isInstanceOf(ProtectedTopologyResourceException.class)
                .hasMessageContaining("solo se permite EARLIEST");
        verifyNoInteractions(adminClient, reachabilityChecker);
    }

    @ParameterizedTest
    @MethodSource("managedGroups")
    void resetOffsets_earliestSobreGrupoDelSistema_pasaLaGuardaYConsultaLosGrupos(String group) {
        // Arrange
        stubListConsumerGroups();
        ResetOffsetsRequest request = new ResetOffsetsRequest("bookings.events", "EARLIEST");

        // Act + Assert
        assertThatThrownBy(() -> service.resetOffsets(group, request))
                .isInstanceOf(AdminResourceNotFoundException.class);
        verify(reachabilityChecker).verifyReachable();
        verify(adminClient).listConsumerGroups();
    }

    @Test
    void createTopic_conConfiguracionNoPermitida_rechazaSinTocarKafka() {
        // Arrange
        CreateTopicRequest request = new CreateTopicRequest("demo.ops.topic", 3, null, Map.of("segment.ms", "1000"));

        // Act + Assert
        assertThatThrownBy(() -> service.createTopic(request))
                .isInstanceOf(InvalidKafkaAdminRequestException.class);
        verifyNoInteractions(adminClient, reachabilityChecker);
    }

    // --- pre-chequeos de existencia/estado ---

    @Test
    void deleteTopic_inexistente_lanza404YNuncaLlamaDeleteTopics() {
        // Arrange
        stubListTopics(Set.of("otro.topico"));

        // Act + Assert
        assertThatThrownBy(() -> service.deleteTopic("demo.ops.topic"))
                .isInstanceOf(AdminResourceNotFoundException.class)
                .hasMessage("No existe un topico llamado 'demo.ops.topic'.");
        verify(adminClient, never()).deleteTopics(anyCollection());
    }

    @Test
    void deleteTopic_existente_borraYEsperaQueDejeDeListarse() {
        // Arrange
        ListTopicsResult present = listTopicsResult(Set.of("demo.ops.topic"));
        ListTopicsResult absent = listTopicsResult(Set.of());
        given(adminClient.listTopics()).willReturn(present, present, absent);
        DeleteTopicsResult deleteResult = mock(DeleteTopicsResult.class);
        given(deleteResult.all()).willReturn(KafkaFuture.completedFuture(null));
        given(adminClient.deleteTopics(List.of("demo.ops.topic"))).willReturn(deleteResult);

        // Act
        service.deleteTopic("demo.ops.topic");

        // Assert
        verify(adminClient).deleteTopics(List.of("demo.ops.topic"));
        verify(adminClient, org.mockito.Mockito.times(3)).listTopics();
    }

    @Test
    void deleteConsumerGroup_inexistente_lanza404YNuncaLlamaDeleteConsumerGroups() {
        // Arrange
        stubListConsumerGroups("otro.grupo");

        // Act + Assert
        assertThatThrownBy(() -> service.deleteConsumerGroup("demo.ops.group"))
                .isInstanceOf(AdminResourceNotFoundException.class)
                .hasMessage("No existe un consumer group llamado 'demo.ops.group'.");
        verify(adminClient, never()).deleteConsumerGroups(anyCollection());
    }

    @Test
    void deleteConsumerGroup_conMiembrosActivos_lanza409() {
        // Arrange
        stubListConsumerGroups("demo.ops.group");
        DeleteConsumerGroupsResult deleteResult = mock(DeleteConsumerGroupsResult.class);
        given(deleteResult.all()).willReturn(failedFuture(new GroupNotEmptyException("not empty")));
        given(adminClient.deleteConsumerGroups(List.of("demo.ops.group"))).willReturn(deleteResult);

        // Act + Assert
        assertThatThrownBy(() -> service.deleteConsumerGroup("demo.ops.group"))
                .isInstanceOf(AdminResourceConflictException.class)
                .hasMessage("El consumer group 'demo.ops.group' tiene miembros activos; "
                        + "detenga sus consumidores antes de eliminarlo.");
    }

    @Test
    void createTopic_conRfMayorQueBrokers_lanza400YNuncaLlamaCreateTopics() {
        // Arrange
        stubBrokers(1);
        CreateTopicRequest request = new CreateTopicRequest("demo.ops.topic", 3, 2, null);

        // Act + Assert
        assertThatThrownBy(() -> service.createTopic(request))
                .isInstanceOf(InvalidKafkaAdminRequestException.class)
                .hasMessage("replicationFactor 2 excede la cantidad de brokers disponibles (1).");
        verify(adminClient, never()).createTopics(anyCollection());
    }

    @Test
    void createTopic_sinRf_usaElDeLasPropiedadesYDevuelveEco() {
        // Arrange
        stubBrokers(1);
        CreateTopicsResult createResult = mock(CreateTopicsResult.class);
        given(createResult.all()).willReturn(KafkaFuture.completedFuture(null));
        given(adminClient.createTopics(anyCollection())).willReturn(createResult);
        CreateTopicRequest request = new CreateTopicRequest(
                "demo.ops.topic", 3, null, Map.of("retention.ms", "86400000"));

        // Act
        AdminTopicInfo result = service.createTopic(request);

        // Assert
        assertThat(result).isEqualTo(new AdminTopicInfo(
                "demo.ops.topic", 3, 1, Map.of("retention.ms", "86400000")));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<NewTopic>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(adminClient).createTopics(captor.capture());
        NewTopic sent = captor.getValue().iterator().next();
        assertThat(sent.replicationFactor()).isEqualTo((short) 1);
        assertThat(sent.numPartitions()).isEqualTo(3);
        assertThat(sent.configs()).containsEntry("retention.ms", "86400000");
    }

    @Test
    void createTopic_sinConfigs_devuelveConfigsVacio() {
        // Arrange
        stubBrokers(1);
        CreateTopicsResult createResult = mock(CreateTopicsResult.class);
        given(createResult.all()).willReturn(KafkaFuture.completedFuture(null));
        given(adminClient.createTopics(anyCollection())).willReturn(createResult);

        // Act
        AdminTopicInfo result = service.createTopic(new CreateTopicRequest("demo.ops.topic", 1, null, null));

        // Assert
        assertThat(result.configs()).isEmpty();
    }

    @Test
    void increasePartitions_conTotalMenorOIgualAlActual_lanza400YNuncaLlamaCreatePartitions() {
        // Arrange
        stubDescribeTopic("demo.ops.topic", 3);

        // Act + Assert
        assertThatThrownBy(() -> service.increasePartitions("demo.ops.topic", new IncreasePartitionsRequest(3)))
                .isInstanceOf(InvalidKafkaAdminRequestException.class)
                .hasMessageContaining("debe ser mayor que la actual (3)");
        verify(adminClient, never()).createPartitions(anyMap());
    }

    @Test
    void resetOffsets_conMiembrosActivos_lanza409YNuncaLlamaAlterConsumerGroupOffsets() {
        // Arrange
        stubListConsumerGroups("demo.ops.group");
        stubDescribeTopic("demo.ops.topic", 1);
        ConsumerGroupDescription description = mock(ConsumerGroupDescription.class);
        given(description.members()).willReturn(List.of(mock(MemberDescription.class)));
        DescribeConsumerGroupsResult describeResult = mock(DescribeConsumerGroupsResult.class);
        given(describeResult.all()).willReturn(KafkaFuture.completedFuture(Map.of("demo.ops.group", description)));
        given(adminClient.describeConsumerGroups(List.of("demo.ops.group"))).willReturn(describeResult);

        // Act + Assert
        assertThatThrownBy(() -> service.resetOffsets(
                        "demo.ops.group", new ResetOffsetsRequest("demo.ops.topic", "EARLIEST")))
                .isInstanceOf(AdminResourceConflictException.class)
                .hasMessageContaining("tiene miembros activos")
                .hasMessageContaining("reiniciar sus offsets");
        verify(adminClient, never()).alterConsumerGroupOffsets(anyString(), anyMap());
    }

    @Test
    void updateTopicConfig_construyeUnSetPorClaveEnviadaYNingunaOtra() {
        // Arrange
        stubListTopics(Set.of("demo.ops.topic"));
        AlterConfigsResult alterResult = mock(AlterConfigsResult.class);
        given(alterResult.all()).willReturn(KafkaFuture.completedFuture(null));
        given(adminClient.incrementalAlterConfigs(anyMap())).willReturn(alterResult);
        Map<String, String> configs = Map.of("retention.ms", "172800000", "cleanup.policy", "compact,delete");

        // Act
        TopicConfigInfo result = service.updateTopicConfig("demo.ops.topic", new UpdateTopicConfigRequest(configs));

        // Assert
        assertThat(result.configs()).isEqualTo(configs);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<ConfigResource, Collection<AlterConfigOp>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(adminClient).incrementalAlterConfigs(captor.capture());
        Map<ConfigResource, Collection<AlterConfigOp>> sent = captor.getValue();
        assertThat(sent).containsOnlyKeys(new ConfigResource(ConfigResource.Type.TOPIC, "demo.ops.topic"));
        Collection<AlterConfigOp> operations = sent.values().iterator().next();
        assertThat(operations).hasSize(2);
        assertThat(operations).allSatisfy(op -> assertThat(op.opType()).isEqualTo(AlterConfigOp.OpType.SET));
        assertThat(operations).extracting(op -> op.configEntry().name() + "=" + op.configEntry().value())
                .containsExactlyInAnyOrder("retention.ms=172800000", "cleanup.policy=compact,delete");
    }

    @Test
    void cualquierOperacion_conKafkaCaido_lanzaKafkaUnavailable() {
        // Arrange
        willThrow(new KafkaUnavailableException(new TimeoutException("timeout")))
                .given(reachabilityChecker).verifyReachable();

        // Act + Assert
        assertThatThrownBy(() -> service.deleteTopic("demo.ops.topic"))
                .isInstanceOf(KafkaUnavailableException.class)
                .hasMessage("Kafka is currently unreachable.");
        verifyNoInteractions(adminClient);
    }

    @Test
    void createTopic_conAdminRespondiendoTimeout_lanzaKafkaUnavailable() {
        // Arrange
        DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
        given(clusterResult.nodes()).willReturn(failedFuture(new TimeoutException("timeout")));
        given(adminClient.describeCluster()).willReturn(clusterResult);

        // Act + Assert
        assertThatThrownBy(() -> service.createTopic(new CreateTopicRequest("demo.ops.topic", 1, null, null)))
                .isInstanceOf(KafkaUnavailableException.class);
        verify(adminClient, never()).createTopics(anyCollection());
    }

    private void stubListTopics(Set<String> names) {
        ListTopicsResult result = listTopicsResult(names);
        given(adminClient.listTopics()).willReturn(result);
    }

    private static ListTopicsResult listTopicsResult(Set<String> names) {
        ListTopicsResult result = mock(ListTopicsResult.class);
        given(result.names()).willReturn(KafkaFuture.completedFuture(names));
        return result;
    }

    private void stubListConsumerGroups(String... groupIds) {
        List<ConsumerGroupListing> listings = Stream.of(groupIds)
                .map(id -> new ConsumerGroupListing(id, false))
                .toList();
        ListConsumerGroupsResult result = mock(ListConsumerGroupsResult.class);
        given(result.all()).willReturn(KafkaFuture.completedFuture(listings));
        given(adminClient.listConsumerGroups()).willReturn(result);
    }

    private void stubBrokers(int count) {
        List<Node> nodes = IntStream.range(0, count).mapToObj(i -> new Node(i, "broker-" + i, 9092)).toList();
        DescribeClusterResult result = mock(DescribeClusterResult.class);
        given(result.nodes()).willReturn(KafkaFuture.completedFuture(nodes));
        given(adminClient.describeCluster()).willReturn(result);
    }

    private void stubDescribeTopic(String name, int partitions) {
        Node node = new Node(0, "broker-0", 9092);
        List<TopicPartitionInfo> infos = IntStream.range(0, partitions)
                .mapToObj(p -> new TopicPartitionInfo(p, node, List.of(node), List.of(node)))
                .toList();
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        given(result.topicNameValues())
                .willReturn(Map.of(name, KafkaFuture.completedFuture(new TopicDescription(name, false, infos))));
        given(adminClient.describeTopics(List.of(name))).willReturn(result);
    }

    private static <T> KafkaFuture<T> failedFuture(Throwable cause) {
        KafkaFutureImpl<T> future = new KafkaFutureImpl<>();
        future.completeExceptionally(cause);
        return future;
    }
}
