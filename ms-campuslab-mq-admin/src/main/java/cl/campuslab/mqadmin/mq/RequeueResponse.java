package cl.campuslab.mqadmin.mq;

public record RequeueResponse(String dlqName, int requestedCount, int actualRequeuedCount, long remainingInDlq) {
}
