package com.example.notifications.controller;

import com.example.notifications.dto.NotifyRequest;
import com.example.notifications.model.Notification;
import com.example.notifications.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller providing endpoints to broadcast notifications and query recent history.
 */
@RestController
@RequestMapping
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * POST /notify
     * Broadcasts a notification to all connected clients and adds it to the recent store.
     */
    @PostMapping("/notify")
    public ResponseEntity<Notification> notify(@Valid @RequestBody NotifyRequest request) {
        Notification notification = notificationService.broadcast(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(notification);
    }

    /**
     * GET /notifications
     * Returns the last 10 notifications in newest-first order.
     */
    @GetMapping("/notifications")
    public ResponseEntity<List<Notification>> getNotifications() {
        return ResponseEntity.ok(notificationService.getRecentNotifications());
    }

    /**
     * GET /connections/count
     * Auxiliary endpoint to query the live WebSocket connection count over REST.
     */
    @GetMapping("/connections/count")
    public ResponseEntity<Map<String, Object>> getConnectionCount() {
        return ResponseEntity.ok(Map.of(
            "count", notificationService.getActiveConnectionCount()
        ));
    }
}
