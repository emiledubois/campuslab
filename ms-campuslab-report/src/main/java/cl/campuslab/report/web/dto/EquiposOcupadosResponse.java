package cl.campuslab.report.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A live, present-tense snapshot, never scoped by {@code range} (design doc §2.3). */
public record EquiposOcupadosResponse(Instant asOf, int count, List<UUID> resourceIds) {
}
