package cl.campuslab.report.web;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * The fixed three-value enum both endpoints accept (design doc §3) - a small fixed
 * enum, not a general ISO-8601 duration parser, deliberately (unneeded generality for
 * a KPI dashboard this project's own scoping discipline argues against building).
 */
public enum ReportRange {

    LAST24H("last24h", Duration.ofHours(24), 24),
    LAST7D("last7d", Duration.ofDays(7), 168),
    LAST30D("last30d", Duration.ofDays(30), 720);

    private final String paramValue;
    private final Duration duration;
    private final int bucketCount;

    ReportRange(String paramValue, Duration duration, int bucketCount) {
        this.paramValue = paramValue;
        this.duration = duration;
        this.bucketCount = bucketCount;
    }

    public String paramValue() {
        return paramValue;
    }

    public Duration duration() {
        return duration;
    }

    public int bucketCount() {
        return bucketCount;
    }

    public static ReportRange fromParam(String raw) {
        return Arrays.stream(values())
                .filter(range -> range.paramValue.equals(raw))
                .findFirst()
                .orElseThrow(() -> new InvalidRangeException(raw));
    }

    public static List<String> acceptedValues() {
        return Arrays.stream(values()).map(ReportRange::paramValue).toList();
    }
}
