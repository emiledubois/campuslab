package cl.campuslab.catalog.service;

import java.util.UUID;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(UUID id) {
        super("No resource with id " + id);
    }
}
