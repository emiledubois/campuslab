package cl.campuslab.report.web.dto;

/** {@code averageSeconds} is {@code null} (not {@code 0}) when zero bookings complete
 * within the range (design doc §2.3) - {@code null} distinguishes "no data" from "an
 * actual cycle time of zero seconds". */
public record TiempoDeCicloResponse(String unit, Double averageSeconds, long completedBookingsCount) {
}
