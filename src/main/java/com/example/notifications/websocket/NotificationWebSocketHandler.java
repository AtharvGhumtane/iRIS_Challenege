package com.example.notifications.websocket;

import com.example.notifications.model.Notification;
import com.example.notifications.service.NotificationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raw WebSocket Handler managing client connections, message broadcasting,
 * and heartbeat ping/pong monitoring.
 */
@Component
public class NotificationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationWebSocketHandler.class);
    private static final String ATTR_LAST_PONG = "lastPongTimestamp";
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 64 * 1024;
    private static final long HEARTBEAT_TIMEOUT_MS = 65_000; // ~2 missed ping intervals (30s each)

    private final NotificationStore notificationStore;
    private final ObjectMapper objectMapper;

    // Thread-safe map of active sessions keyed by session ID
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public NotificationWebSocketHandler(NotificationStore notificationStore, ObjectMapper objectMapper) {
        this.notificationStore = notificationStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // Decorate session to ensure thread-safe sendMessage operations
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
            session,
            SEND_TIME_LIMIT_MS,
            BUFFER_SIZE_LIMIT_BYTES
        );

        safeSession.getAttributes().put(ATTR_LAST_PONG, System.currentTimeMillis());
        sessions.put(safeSession.getId(), safeSession);

        log.info("Client connected: id={} from {}. Total connected: {}",
            safeSession.getId(), safeSession.getRemoteAddress(), sessions.size());

        // 1. Send recent notification history to the newly connected client
        sendHistoryToSession(safeSession);

        // 2. Broadcast updated connection count to all clients
        broadcastConnectionCount();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        log.info("Client disconnected: id={}, status={}. Total connected: {}",
            session.getId(), status, sessions.size());

        broadcastConnectionCount();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("Transport error on session {}: {}", session.getId(), exception.getMessage());
        sessions.remove(session.getId());
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException e) {
            log.debug("Error while closing failed session {}: {}", session.getId(), e.getMessage());
        }
        broadcastConnectionCount();
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) throws Exception {
        session.getAttributes().put(ATTR_LAST_PONG, System.currentTimeMillis());
        log.debug("Received pong from session {}", session.getId());
    }

    /**
     * Broadcasts a notification to all currently connected, open WebSocket sessions.
     * If sending to a specific client fails, the exception is logged, the bad session
     * is removed, and the broadcast continues to the other clients without interruption.
     */
    public void broadcastNotification(Notification notification) {
        String payload;
        try {
            Map<String, Object> message = Map.of(
                "type", "notification",
                "notification", notification
            );
            payload = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("Failed to serialize notification for broadcast", e);
            return;
        }

        TextMessage textMessage = new TextMessage(payload);
        log.info("Broadcasting notification [{}] to {} sessions", notification.title(), sessions.size());

        for (WebSocketSession session : sessions.values()) {
            sendSafely(session, textMessage);
        }
    }

    /**
     * Broadcasts the current active connection count to all connected clients.
     */
    public void broadcastConnectionCount() {
        int count = sessions.size();
        String payload;
        try {
            Map<String, Object> message = Map.of(
                "type", "connectionCount",
                "count", count
            );
            payload = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("Failed to serialize connection count", e);
            return;
        }

        TextMessage textMessage = new TextMessage(payload);
        for (WebSocketSession session : sessions.values()) {
            sendSafely(session, textMessage);
        }
    }

    /**
     * Sends the last 10 notifications history to a single connected session.
     */
    private void sendHistoryToSession(WebSocketSession session) {
        List<Notification> recent = notificationStore.getRecent();
        try {
            Map<String, Object> message = Map.of(
                "type", "history",
                "notifications", recent
            );
            String payload = objectMapper.writeValueAsString(message);
            sendSafely(session, new TextMessage(payload));
        } catch (Exception e) {
            log.error("Failed to serialize or send history to session {}", session.getId(), e);
        }
    }

    /**
     * Sends a WebSocket message safely. Removes and closes the session if transmission fails.
     */
    private void sendSafely(WebSocketSession session, TextMessage message) {
        if (!session.isOpen()) {
            sessions.remove(session.getId());
            return;
        }

        try {
            session.sendMessage(message);
        } catch (Exception e) {
            log.warn("Failed to send message to session {}, removing: {}", session.getId(), e.getMessage());
            sessions.remove(session.getId());
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Heartbeat task: runs every 30 seconds.
     * Pings all connected sessions. Closes and removes sessions that did not respond
     * with a pong within the timeout threshold.
     */
    @Scheduled(fixedDelay = 30000)
    public void heartbeat() {
        long now = System.currentTimeMillis();
        boolean countChanged = false;

        for (WebSocketSession session : sessions.values()) {
            if (!session.isOpen()) {
                sessions.remove(session.getId());
                countChanged = true;
                continue;
            }

            Long lastPong = (Long) session.getAttributes().get(ATTR_LAST_PONG);
            if (lastPong != null && (now - lastPong) > HEARTBEAT_TIMEOUT_MS) {
                log.warn("Session {} timed out on heartbeat (last pong {}ms ago), closing",
                    session.getId(), now - lastPong);
                sessions.remove(session.getId());
                try {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (IOException ignored) {
                }
                countChanged = true;
                continue;
            }

            try {
                session.sendMessage(new PingMessage(ByteBuffer.wrap(new byte[0])));
            } catch (Exception e) {
                log.warn("Failed to send ping to session {}: {}", session.getId(), e.getMessage());
                sessions.remove(session.getId());
                try {
                    session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                }
                countChanged = true;
            }
        }

        if (countChanged) {
            broadcastConnectionCount();
        }
    }

    /**
     * Returns the current number of active WebSocket sessions.
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * Package-private or public method for unit testing session map operations.
     */
    Map<String, WebSocketSession> getSessions() {
        return sessions;
    }
}
