package cl.campuslab.mqadmin.mq;

/** Response for binding create (Slice A design doc §3.7/§3.10) - echoes the four fields back. */
public record AdminBindingInfo(String source, String destination, String destinationType, String routingKey) {
}
