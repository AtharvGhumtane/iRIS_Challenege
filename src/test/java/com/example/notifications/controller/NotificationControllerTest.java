package com.example.notifications.controller;

import com.example.notifications.dto.NotifyRequest;
import com.example.notifications.model.Notification;
import com.example.notifications.model.Severity;
import com.example.notifications.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private NotificationService notificationService;

    @Test
    @DisplayName("POST /notify with valid payload returns 201 Created and serialized notification")
    void testValidNotifyRequest() throws Exception {
        Notification dummy = new Notification(
            "123-uuid",
            "Server Restart",
            "Server is restarting soon",
            Severity.WARNING,
            Instant.now()
        );
        when(notificationService.broadcast(any(NotifyRequest.class))).thenReturn(dummy);

        String json = """
            {
                "title": "Server Restart",
                "message": "Server is restarting soon",
                "severity": "warning"
            }
            """;

        mockMvc.perform(post("/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("123-uuid"))
                .andExpect(jsonPath("$.title").value("Server Restart"))
                .andExpect(jsonPath("$.severity").value("warning"));
    }

    @Test
    @DisplayName("POST /notify with blank title returns 400 Bad Request with field error")
    void testBlankTitleValidation() throws Exception {
        String json = """
            {
                "title": "   ",
                "message": "Some valid message",
                "severity": "info"
            }
            """;

        mockMvc.perform(post("/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.title").exists());
    }

    @Test
    @DisplayName("POST /notify with blank message returns 400 Bad Request with field error")
    void testBlankMessageValidation() throws Exception {
        String json = """
            {
                "title": "Valid Title",
                "message": "",
                "severity": "info"
            }
            """;

        mockMvc.perform(post("/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.message").exists());
    }

    @Test
    @DisplayName("POST /notify with invalid severity returns 400 Bad Request with helpful error message")
    void testInvalidSeverityValidation() throws Exception {
        String json = """
            {
                "title": "Alert",
                "message": "Something happened",
                "severity": "unknown_sev"
            }
            """;

        mockMvc.perform(post("/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid severity value. Allowed values are: info, warning, critical."));
    }

    @Test
    @DisplayName("POST /notify with malformed JSON returns 400 Bad Request")
    void testMalformedJson() throws Exception {
        String badJson = "{ invalid-json }";

        mockMvc.perform(post("/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(badJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /notifications returns 200 OK and list of recent notifications")
    void testGetNotifications() throws Exception {
        Notification item = new Notification(
            "id-1",
            "Update",
            "Details",
            Severity.INFO,
            Instant.now()
        );
        when(notificationService.getRecentNotifications()).thenReturn(List.of(item));

        mockMvc.perform(get("/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("id-1"))
                .andExpect(jsonPath("$[0].title").value("Update"))
                .andExpect(jsonPath("$[0].severity").value("info"));
    }
}
