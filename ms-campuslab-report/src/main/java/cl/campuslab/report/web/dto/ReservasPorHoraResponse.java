package cl.campuslab.report.web.dto;

import java.util.List;

/** Every hour bucket in the requested range appears, including zero-count hours
 * (design doc §2.3) - {@code bucketCount} is always {@code buckets.size()}. */
public record ReservasPorHoraResponse(int bucketCount, List<BucketResponse> buckets) {
}
