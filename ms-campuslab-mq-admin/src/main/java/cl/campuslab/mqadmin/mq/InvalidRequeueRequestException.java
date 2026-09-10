package cl.campuslab.mqadmin.mq;

/** Maps to 400 (design doc §3) - the request body did not specify exactly one of
 * {@code count}/{@code all}, or {@code count} was not strictly positive. */
public class InvalidRequeueRequestException extends RuntimeException {

    public InvalidRequeueRequestException(String message) {
        super(message);
    }
}
