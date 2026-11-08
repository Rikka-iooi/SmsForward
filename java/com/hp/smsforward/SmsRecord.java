package com.hp.smsforward;

/**
 * One received SMS and its forwarding state.
 */
public final class SmsRecord {
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SENDING = "sending";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_FAILED = "failed";

    public final long id;
    public final long receivedAt;
    public final String sender;
    public final String body;
    public final String status;
    public final int attempts;
    public final String error;
    public final String channel;
    public final Long sentAt;

    public SmsRecord(long id, long receivedAt, String sender, String body, String status,
                     int attempts, String error, String channel, Long sentAt) {
        this.id = id;
        this.receivedAt = receivedAt;
        this.sender = sender;
        this.body = body;
        this.status = status;
        this.attempts = attempts;
        this.error = error;
        this.channel = channel;
        this.sentAt = sentAt;
    }

    public String preview(int maxChars) {
        if (body == null) return "";
        String oneLine = body.replace('\r', ' ').replace('\n', ' ').trim();
        if (oneLine.length() <= maxChars) return oneLine;
        return oneLine.substring(0, Math.max(0, maxChars - 1)) + "…";
    }
}
