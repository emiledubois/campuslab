package cl.campuslab.report.web.dto;

import java.time.Instant;
import java.util.List;

/** {@code resources} is never zero-padded - a resource with zero {@code
 * BOOKING_APROBADA} events in range simply doesn't appear (design doc §2.3). */
public record TopResourcesResponse(String range, Instant generatedAt, List<ResourceApprovalCountResponse> resources) {
}
