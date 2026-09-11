package cl.campuslab.report.web.dto;

import java.time.Instant;

public record BucketResponse(Instant hourStart, long count) {
}
