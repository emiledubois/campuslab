package cl.campuslab.report.web.dto;

import java.util.UUID;

public record ResourceApprovalCountResponse(UUID resourceId, long approvedCount) {
}
