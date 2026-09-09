package cl.campuslab.catalog.service;

/**
 * Raised when stock/cupo don't match the shape required for a resourceType
 * (LABORATORIO needs cupo/no stock, EQUIPO/INSUMO need stock/no cupo) - mapped to
 * 400 by ApiExceptionHandler. The DB's CHECK constraints are a defense-in-depth
 * backstop for this same rule, not the primary gate.
 */
public class InvalidResourceShapeException extends RuntimeException {

    public InvalidResourceShapeException(String message) {
        super(message);
    }
}
