package cl.campuslab.mqadmin.mq;

/** Response for exchange create (Slice A design doc §3.5/§3.10). */
public record AdminExchangeInfo(String name, String type, boolean durable, boolean autoDelete) {
}
