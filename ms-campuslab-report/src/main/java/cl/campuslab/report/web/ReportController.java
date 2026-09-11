package cl.campuslab.report.web;

import cl.campuslab.report.web.dto.KpisResponse;
import cl.campuslab.report.web.dto.TopResourcesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role enforcement itself lives in the path rule in SecurityConfig (A01) - ADMIN
 * only, read-only (design doc §3/§5's "solo lectura" table entry - no write endpoint
 * exists on report, by design). No ownership check beyond the role gate: KPIs and
 * top-resources are aggregate, cross-user-by-nature figures, not any individual's data.
 */
@RestController
@RequestMapping("/api/report")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportQueryService queryService;

    public ReportController(ReportQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/kpis")
    public KpisResponse kpis(
            @RequestParam(required = false, defaultValue = "last24h") String range,
            @AuthenticationPrincipal Jwt jwt) {
        log.info("GET /api/report/kpis: callerOid=[{}] range=[{}]", oidOf(jwt), range);
        return queryService.kpis(range);
    }

    @GetMapping("/top-resources")
    public TopResourcesResponse topResources(
            @RequestParam(required = false, defaultValue = "last7d") String range,
            @AuthenticationPrincipal Jwt jwt) {
        log.info("GET /api/report/top-resources: callerOid=[{}] range=[{}]", oidOf(jwt), range);
        return queryService.topResources(range);
    }

    private static String oidOf(Jwt jwt) {
        return jwt != null ? jwt.getClaimAsString("oid") : null;
    }
}
