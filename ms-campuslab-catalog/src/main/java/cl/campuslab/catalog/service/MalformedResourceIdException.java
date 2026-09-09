package cl.campuslab.catalog.service;

/** Raised when a path {@code {id}} is not a valid UUID - mapped to 400 by ApiExceptionHandler. */
public class MalformedResourceIdException extends RuntimeException {

    public MalformedResourceIdException(String id) {
        super("'" + id + "' is not a valid resource id");
    }
}
