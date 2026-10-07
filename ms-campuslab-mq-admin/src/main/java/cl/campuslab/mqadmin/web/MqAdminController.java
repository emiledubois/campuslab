package cl.campuslab.mqadmin.web;

import cl.campuslab.mqadmin.mq.AdminBindingInfo;
import cl.campuslab.mqadmin.mq.AdminExchangeInfo;
import cl.campuslab.mqadmin.mq.AdminQueueInfo;
import cl.campuslab.mqadmin.mq.AdminResourceNotFoundException;
import cl.campuslab.mqadmin.mq.BindingRequest;
import cl.campuslab.mqadmin.mq.CreateExchangeRequest;
import cl.campuslab.mqadmin.mq.CreateQueueRequest;
import cl.campuslab.mqadmin.mq.ProtectedTopologyResourceException;
import cl.campuslab.mqadmin.mq.PurgeResult;
import cl.campuslab.mqadmin.mq.QueueInfo;
import cl.campuslab.mqadmin.mq.RabbitAdminService;
import cl.campuslab.mqadmin.mq.RequeueRequest;
import cl.campuslab.mqadmin.mq.RequeueResponse;
import cl.campuslab.mqadmin.mq.RequeueService;
import cl.campuslab.mqadmin.mq.QueueInspectionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role enforcement itself lives in the path rule in SecurityConfig (A01) - ADMIN-only,
 * no secondary read role (unlike catalog's ADMIN+TECNICO split, design doc §3/§8). Slice A
 * (mq-admin-endpoints.md) adds 7 imperative create/delete/purge methods below the
 * pre-existing GET /queues and POST /dlq/.../requeue, which are unmodified.
 */
@RestController
@RequestMapping("/api/admin/mq")
public class MqAdminController {

    private static final Logger log = LoggerFactory.getLogger(MqAdminController.class);

    private final QueueInspectionService inspectionService;
    private final RequeueService requeueService;
    private final RabbitAdminService rabbitAdminService;

    public MqAdminController(
            QueueInspectionService inspectionService, RequeueService requeueService, RabbitAdminService rabbitAdminService) {
        this.inspectionService = inspectionService;
        this.requeueService = requeueService;
        this.rabbitAdminService = rabbitAdminService;
    }

    @GetMapping("/queues")
    public List<QueueInfo> queues() {
        return inspectionService.listQueues();
    }

    @PostMapping("/dlq/{dlqName}/requeue")
    public RequeueResponse requeue(
            @PathVariable String dlqName,
            @RequestBody(required = false) RequeueRequest request,
            JwtAuthenticationToken authentication) {
        return requeueService.requeue(dlqName, request, callerOidOf(authentication));
    }

    @PostMapping("/queues")
    public ResponseEntity<AdminQueueInfo> createQueue(
            @Valid @RequestBody CreateQueueRequest request, JwtAuthenticationToken authentication) {
        AdminQueueInfo result = audited(authentication, "create", "queue", request.name(),
                () -> rabbitAdminService.createQueue(request), info -> "CREATED");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @DeleteMapping("/queues/{name}")
    public ResponseEntity<Void> deleteQueue(@PathVariable String name, JwtAuthenticationToken authentication) {
        audited(authentication, "delete", "queue", name, () -> {
            rabbitAdminService.deleteQueue(name);
            return null;
        }, ignored -> "DELETED");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/queues/{name}/purge")
    public ResponseEntity<PurgeResult> purgeQueue(@PathVariable String name, JwtAuthenticationToken authentication) {
        PurgeResult result = audited(authentication, "purge", "queue", name,
                () -> rabbitAdminService.purgeQueue(name), r -> "PURGED(" + r.purgedMessageCount() + ")");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/exchanges")
    public ResponseEntity<AdminExchangeInfo> createExchange(
            @Valid @RequestBody CreateExchangeRequest request, JwtAuthenticationToken authentication) {
        AdminExchangeInfo result = audited(authentication, "create", "exchange", request.name(),
                () -> rabbitAdminService.createExchange(request), info -> "CREATED");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @DeleteMapping("/exchanges/{name}")
    public ResponseEntity<Void> deleteExchange(@PathVariable String name, JwtAuthenticationToken authentication) {
        audited(authentication, "delete", "exchange", name, () -> {
            rabbitAdminService.deleteExchange(name);
            return null;
        }, ignored -> "DELETED");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bindings")
    public ResponseEntity<AdminBindingInfo> createBinding(
            @Valid @RequestBody BindingRequest request, JwtAuthenticationToken authentication) {
        AdminBindingInfo result = audited(authentication, "create", "binding", bindingNameOf(request),
                () -> rabbitAdminService.createBinding(request), info -> "CREATED");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @DeleteMapping("/bindings")
    public ResponseEntity<Void> deleteBinding(
            @Valid @RequestBody BindingRequest request, JwtAuthenticationToken authentication) {
        audited(authentication, "delete", "binding", bindingNameOf(request), () -> {
            rabbitAdminService.deleteBinding(request);
            return null;
        }, ignored -> "DELETED");
        return ResponseEntity.noContent().build();
    }

    private static String bindingNameOf(BindingRequest request) {
        return request.source() + " -> " + request.destination();
    }

    /** Shared audit-logging wrapper (design doc §9 A09) - every create/delete/purge/
     * binding call logs INFO with caller oid/operation/resource type/name/outcome, and
     * every ProtectedTopologyResourceException rejection additionally logs WARN, since an
     * ADMIN attempting to mutate a system-managed resource is security-relevant even
     * though it is correctly authorized and correctly rejected. */
    private <T> T audited(
            JwtAuthenticationToken authentication, String operation, String resourceType, String name,
            Supplier<T> action, java.util.function.Function<T, String> outcomeOf) {
        String oid = callerOidOf(authentication);
        try {
            T result = action.get();
            log.info("Mq admin op: oid=[{}] op=[{}] type=[{}] name=[{}] outcome=[{}]",
                    oid, operation, resourceType, name, outcomeOf.apply(result));
            return result;
        } catch (ProtectedTopologyResourceException ex) {
            log.info("Mq admin op: oid=[{}] op=[{}] type=[{}] name=[{}] outcome=[REJECTED_PROTECTED]",
                    oid, operation, resourceType, name);
            log.warn("Protected topology resource rejection: oid=[{}] op=[{}] type=[{}] name=[{}] reason=[{}]",
                    oid, operation, resourceType, name, ex.getMessage());
            throw ex;
        } catch (AdminResourceNotFoundException ex) {
            log.info("Mq admin op: oid=[{}] op=[{}] type=[{}] name=[{}] outcome=[NOT_FOUND]",
                    oid, operation, resourceType, name);
            throw ex;
        }
    }

    /** Display/logging only (design doc §3's audit-logging requirement) - falls back to
     * {@code sub} defensively, same convention as bookings'/BFF's own display-only oid. */
    private static String callerOidOf(JwtAuthenticationToken authentication) {
        String oid = authentication.getToken().getClaimAsString("oid");
        return (oid != null && !oid.isBlank()) ? oid : authentication.getToken().getSubject();
    }
}
