package com.example.notifications.dto;

import com.example.notifications.model.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request payload for creating and broadcasting a notification.
 *
 * @param title    Required, max 100 characters.
 * @param message  Required, max 500 characters.
 * @param severity Required severity (info, warning, or critical).
 */
public record NotifyRequest(
    @NotBlank(message = "Title is required and cannot be blank")
    @Size(min = 1, max = 100, message = "Title must be between 1 and 100 characters")
    String title,

    @NotBlank(message = "Message is required and cannot be blank")
    @Size(min = 1, max = 500, message = "Message must be between 1 and 500 characters")
    String message,

    @NotNull(message = "Severity is required (allowed values: info, warning, critical)")
    Severity severity
) {}
