package com.example.notifications.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable Notification domain model.
 *
 * @param id        Unique identifier for the notification (UUID).
 * @param title     Brief summary or title.
 * @param message   Notification body text.
 * @param severity  Severity level (INFO, WARNING, CRITICAL).
 * @param timestamp Instant in time when the notification was created.
 */
public record Notification(
    String id,
    String title,
    String message,
    Severity severity,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant timestamp
) {
    /**
     * Factory constructor for creating a new Notification with auto-generated UUID and current timestamp.
     */
    public static Notification of(String title, String message, Severity severity) {
        return new Notification(
            UUID.randomUUID().toString(),
            title,
            message,
            severity,
            Instant.now()
        );
    }
}
