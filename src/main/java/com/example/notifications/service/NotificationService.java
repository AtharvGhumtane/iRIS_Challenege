package com.example.notifications.service;

import com.example.notifications.dto.NotifyRequest;
import com.example.notifications.model.Notification;
import com.example.notifications.websocket.NotificationWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Core business service for notifications.
 * Enforces the single unified path for broadcasting notifications:
 * Validate -> Assign id & timestamp -> Store in recent history -> Push via WebSocket.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationStore notificationStore;
    private final NotificationWebSocketHandler webSocketHandler;

    public NotificationService(NotificationStore notificationStore,
                               NotificationWebSocketHandler webSocketHandler) {
        this.notificationStore = notificationStore;
        this.webSocketHandler = webSocketHandler;
    }

    /**
     * Single shared method for broadcasting a notification across all entry points.
     *
     * @param request the validated notification request DTO
     * @return the created Notification instance
     */
    public Notification broadcast(NotifyRequest request) {
        // Create domain notification with unique ID and timestamp
        Notification notification = Notification.of(
            request.title(),
            request.message(),
            request.severity()
        );

        // Store in thread-safe recent history (capped at 10)
        notificationStore.add(notification);

        // Push real-time event to all connected WebSocket clients
        webSocketHandler.broadcastNotification(notification);

        log.info("Broadcasted notification id={}, severity={}, title='{}'",
            notification.id(), notification.severity(), notification.title());

        return notification;
    }

    /**
     * Retrieves the last 10 notifications from the in-memory store in newest-first order.
     */
    public List<Notification> getRecentNotifications() {
        return notificationStore.getRecent();
    }

    /**
     * Returns the count of currently active WebSocket clients.
     */
    public int getActiveConnectionCount() {
        return webSocketHandler.getActiveSessionCount();
    }
}
