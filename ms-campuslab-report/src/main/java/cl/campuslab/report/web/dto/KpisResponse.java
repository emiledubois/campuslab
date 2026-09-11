package cl.campuslab.report.web.dto;

import java.time.Instant;

public record KpisResponse(
        String range,
        Instant generatedAt,
        ReservasPorHoraResponse reservasPorHora,
        TiempoDeCicloResponse tiempoDeCiclo,
        EquiposOcupadosResponse equiposOcupados) {
}
