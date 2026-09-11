package cl.campuslab.report.web;

/**
 * The single 400 case both {@code GET /api/report/kpis} and {@code GET
 * /api/report/top-resources} share (design doc §3) - a {@code range} value outside
 * the fixed {@code last24h}/{@code last7d}/{@code last30d} enum.
 */
public class InvalidRangeException extends RuntimeException {

    public InvalidRangeException(String rawValue) {
        super("'range' must be one of " + ReportRange.acceptedValues() + ", got: " + rawValue);
    }
}
