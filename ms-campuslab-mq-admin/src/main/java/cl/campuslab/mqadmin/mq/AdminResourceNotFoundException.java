package cl.campuslab.mqadmin.mq;

/**
 * Maps to 404 (Slice A design doc §3.3/§3.4/§3.5/§3.7) - no queue/exchange with that
 * name exists, or a binding's queue-typed destination does not exist.
 */
public class AdminResourceNotFoundException extends RuntimeException {

    public AdminResourceNotFoundException(String resourceType, String name) {
        super("No " + resourceType + " named '" + name + "' exists.");
    }
}
