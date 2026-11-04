package cl.campuslab.kafkaadmin.web;

import cl.campuslab.kafkaadmin.kafka.AdminResourceConflictException;
import cl.campuslab.kafkaadmin.kafka.AdminResourceNotFoundException;
import cl.campuslab.kafkaadmin.kafka.AdminTopicInfo;
import cl.campuslab.kafkaadmin.kafka.ConsumerGroupInfo;
import cl.campuslab.kafkaadmin.kafka.ConsumerGroupInspectionService;
import cl.campuslab.kafkaadmin.kafka.CreateTopicRequest;
import cl.campuslab.kafkaadmin.kafka.DltInfo;
import cl.campuslab.kafkaadmin.kafka.DltInspectionService;
import cl.campuslab.kafkaadmin.kafka.IncreasePartitionsRequest;
import cl.campuslab.kafkaadmin.kafka.InvalidKafkaAdminRequestException;
import cl.campuslab.kafkaadmin.kafka.KafkaAdminService;
import cl.campuslab.kafkaadmin.kafka.OffsetResetResult;
import cl.campuslab.kafkaadmin.kafka.PartitionChangeInfo;
import cl.campuslab.kafkaadmin.kafka.ProtectedTopologyResourceException;
import cl.campuslab.kafkaadmin.kafka.ResetOffsetsRequest;
import cl.campuslab.kafkaadmin.kafka.TopicConfigInfo;
import cl.campuslab.kafkaadmin.kafka.TopicInfo;
import cl.campuslab.kafkaadmin.kafka.TopologyInspectionService;
import cl.campuslab.kafkaadmin.kafka.UpdateTopicConfigRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role enforcement itself lives in the path rule in SecurityConfig (A01) - ADMIN-only,
 * no secondary read role (design doc §3/§7 A01). kafka-admin-write.md agrega 6 operaciones
 * con escritura debajo de los 3 GET existentes, que no cambian.
 */
@RestController
@RequestMapping("/api/admin/kafka")
public class KafkaAdminController {

    private static final Logger log = LoggerFactory.getLogger(KafkaAdminController.class);

    private final TopologyInspectionService topologyInspectionService;
    private final ConsumerGroupInspectionService consumerGroupInspectionService;
    private final DltInspectionService dltInspectionService;
    private final KafkaAdminService kafkaAdminService;

    public KafkaAdminController(
            TopologyInspectionService topologyInspectionService,
            ConsumerGroupInspectionService consumerGroupInspectionService,
            DltInspectionService dltInspectionService,
            KafkaAdminService kafkaAdminService) {
        this.topologyInspectionService = topologyInspectionService;
        this.consumerGroupInspectionService = consumerGroupInspectionService;
        this.dltInspectionService = dltInspectionService;
        this.kafkaAdminService = kafkaAdminService;
    }

    @GetMapping("/topics")
    public List<TopicInfo> topics() {
        return topologyInspectionService.listTopics();
    }

    @GetMapping("/consumer-groups")
    public List<ConsumerGroupInfo> consumerGroups() {
        return consumerGroupInspectionService.listGroups();
    }

    @GetMapping("/dlt")
    public List<DltInfo> dlt() {
        return dltInspectionService.listDlts();
    }

    @PostMapping("/topics")
    public ResponseEntity<AdminTopicInfo> createTopic(
            @Valid @RequestBody CreateTopicRequest request, JwtAuthenticationToken authentication) {
        AdminTopicInfo result = audited(authentication, "create", "topic", request.name(),
                () -> kafkaAdminService.createTopic(request), info -> "CREATED");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @DeleteMapping("/topics/{name}")
    public ResponseEntity<Void> deleteTopic(@PathVariable String name, JwtAuthenticationToken authentication) {
        audited(authentication, "delete", "topic", name, () -> {
            kafkaAdminService.deleteTopic(name);
            return null;
        }, ignored -> "DELETED");
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/topics/{name}/config")
    public ResponseEntity<TopicConfigInfo> updateTopicConfig(
            @PathVariable String name, @Valid @RequestBody UpdateTopicConfigRequest request,
            JwtAuthenticationToken authentication) {
        TopicConfigInfo result = audited(authentication, "update-config", "topic", name,
                () -> kafkaAdminService.updateTopicConfig(name, request),
                info -> "CONFIG_UPDATED(" + String.join(",", info.configs().keySet().stream().sorted().toList()) + ")");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/topics/{name}/partitions")
    public ResponseEntity<PartitionChangeInfo> increasePartitions(
            @PathVariable String name, @Valid @RequestBody IncreasePartitionsRequest request,
            JwtAuthenticationToken authentication) {
        PartitionChangeInfo result = audited(authentication, "increase-partitions", "topic", name,
                () -> kafkaAdminService.increasePartitions(name, request),
                info -> "PARTITIONS(" + info.previousPartitions() + "->" + info.partitions() + ")");
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/consumer-groups/{groupId}")
    public ResponseEntity<Void> deleteConsumerGroup(
            @PathVariable String groupId, JwtAuthenticationToken authentication) {
        audited(authentication, "delete-group", "consumer-group", groupId, () -> {
            kafkaAdminService.deleteConsumerGroup(groupId);
            return null;
        }, ignored -> "DELETED");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/consumer-groups/{groupId}/reset-offsets")
    public ResponseEntity<OffsetResetResult> resetOffsets(
            @PathVariable String groupId, @Valid @RequestBody ResetOffsetsRequest request,
            JwtAuthenticationToken authentication) {
        OffsetResetResult result = audited(authentication, "reset-offsets", "consumer-group", groupId,
                () -> kafkaAdminService.resetOffsets(groupId, request),
                r -> "RESET(" + r.strategy() + "," + r.topic() + ")");
        return ResponseEntity.ok(result);
    }

    /** Copia del wrapper de MqAdminController (A09): INFO por operacion con oid/op/tipo/
     * nombre/resultado, y WARN adicional en cada rechazo por proteccion de topologia. */
    private <T> T audited(
            JwtAuthenticationToken authentication, String operation, String resourceType, String name,
            Supplier<T> action, Function<T, String> outcomeOf) {
        String oid = callerOidOf(authentication);
        try {
            T result = action.get();
            log.info("Kafka admin op: oid=[{}] op=[{}] type=[{}] name=[{}] outcome=[{}]",
                    oid, operation, resourceType, name, outcomeOf.apply(result));
            return result;
        } catch (ProtectedTopologyResourceException ex) {
            log.info("Kafka admin op: oid=[{}] op=[{}] type=[{}] name=[{}] outcome=[REJECTED_PROTECTED]",
                    oid, operation, resourceType, name);
            log.warn("Protected topology resource rejection: oid=[{}] op=[{}] type=[{}] name=[{}] reason=[{}]",
                    oid, operation, resourceType, name, ex.getMessage());
            throw ex;
        } catch (AdminResourceNotFoundException ex) {
            log.info("Kafka admin op: oid=[{}] op=[{}] type=[{}] name=[{}] outcome=[NOT_FOUND]",
                    oid, operation, resourceType, name);
            throw ex;
        } catch (AdminResourceConflictException ex) {
            log.info("Kafka admin op: oid=[{}] op=[{}] type=[{}] name=[{}] outcome=[CONFLICT]",
                    oid, operation, resourceType, name);
            throw ex;
        } catch (InvalidKafkaAdminRequestException ex) {
            log.info("Kafka admin op: oid=[{}] op=[{}] type=[{}] name=[{}] outcome=[INVALID]",
                    oid, operation, resourceType, name);
            throw ex;
        }
    }

    /** Solo para logging; cae a {@code sub} de forma defensiva, misma convencion que mq-admin. */
    private static String callerOidOf(JwtAuthenticationToken authentication) {
        String oid = authentication.getToken().getClaimAsString("oid");
        return (oid != null && !oid.isBlank()) ? oid : authentication.getToken().getSubject();
    }
}
