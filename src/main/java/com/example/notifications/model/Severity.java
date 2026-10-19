package com.example.notifications.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Severity level for notifications.
 * Serializes to lowercase string: "info", "warning", "critical".
 */
public enum Severity {
    INFO,
    WARNING,
    CRITICAL;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static Severity fromJson(String value) {
        if (value == null) {
            return null;
        }
        for (Severity s : values()) {
            if (s.name().equalsIgnoreCase(value.trim())) {
                return s;
            }
        }
        throw new IllegalArgumentException(
            "Invalid severity: '" + value + "'. Allowed values are: info, warning, critical."
        );
    }
}
