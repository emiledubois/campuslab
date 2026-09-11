package cl.campuslab.audit.web;

import cl.campuslab.audit.web.dto.TimelineEventResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role enforcement itself lives in the path rule in SecurityConfig (A01) - ADMIN/
 * AUDITOR only, read-only (design doc §3/§5's "solo lectura" table entry - no write
 * endpoint exists on audit, by design). No ownership check beyond the role gate: both
 * roles are inherently cross-user read roles by the case document's own definition.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditTimelineController {

    private final TimelineQueryService queryService;

    public AuditTimelineController(TimelineQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/timeline")
    public List<TimelineEventResponse> timeline(
            @RequestParam(required = false) String userOid,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String bookingId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit) {
        return queryService.query(userOid, eventType, bookingId, from, to, limit);
    }
}
