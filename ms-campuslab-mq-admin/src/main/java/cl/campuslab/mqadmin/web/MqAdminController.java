package cl.campuslab.mqadmin.web;

import cl.campuslab.mqadmin.mq.QueueInfo;
import cl.campuslab.mqadmin.mq.RequeueRequest;
import cl.campuslab.mqadmin.mq.RequeueResponse;
import cl.campuslab.mqadmin.mq.RequeueService;
import cl.campuslab.mqadmin.mq.QueueInspectionService;
import java.util.List;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role enforcement itself lives in the path rule in SecurityConfig (A01) - ADMIN-only,
 * no secondary read role (unlike catalog's ADMIN+TECNICO split, design doc §3/§8).
 */
@RestController
@RequestMapping("/api/admin/mq")
public class MqAdminController {

    private final QueueInspectionService inspectionService;
    private final RequeueService requeueService;

    public MqAdminController(QueueInspectionService inspectionService, RequeueService requeueService) {
        this.inspectionService = inspectionService;
        this.requeueService = requeueService;
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

    /** Display/logging only (design doc §3's audit-logging requirement) - falls back to
     * {@code sub} defensively, same convention as bookings'/BFF's own display-only oid. */
    private static String callerOidOf(JwtAuthenticationToken authentication) {
        String oid = authentication.getToken().getClaimAsString("oid");
        return (oid != null && !oid.isBlank()) ? oid : authentication.getToken().getSubject();
    }
}
