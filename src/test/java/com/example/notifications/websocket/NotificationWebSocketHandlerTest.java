package com.example.notifications.websocket;

import com.example.notifications.model.Notification;
import com.example.notifications.model.Severity;
import com.example.notifications.service.NotificationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationWebSocketHandlerTest {

    private NotificationStore notificationStore;
    private ObjectMapper objectMapper;
    private NotificationWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        notificationStore = mock(NotificationStore.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        handler = new NotificationWebSocketHandler(notificationStore, objectMapper);
    }

    @Test
    @DisplayName("Should skip closed sessions during broadcast and clean them up")
    void testBroadcastSkipsClosedSessions() throws IOException {
        WebSocketSession openSession = mock(WebSocketSession.class);
        when(openSession.getId()).thenReturn("open-1");
        when(openSession.isOpen()).thenReturn(true);
        when(openSession.getAttributes()).thenReturn(new HashMap<>());

        WebSocketSession closedSession = mock(WebSocketSession.class);
        when(closedSession.getId()).thenReturn("closed-2");
        when(closedSession.isOpen()).thenReturn(false);
        when(closedSession.getAttributes()).thenReturn(new HashMap<>());

        handler.getSessions().put("open-1", openSession);
        handler.getSessions().put("closed-2", closedSession);

        Notification notification = new Notification("n1", "Hello", "World", Severity.INFO, Instant.now());
        handler.broadcastNotification(notification);

        // Open session must have received the message
        verify(openSession, times(1)).sendMessage(any(TextMessage.class));

        // Closed session must never have been sent a message
        verify(closedSession, never()).sendMessage(any(TextMessage.class));

        // Closed session should be removed from active sessions
        assertFalse(handler.getSessions().containsKey("closed-2"));
        assertTrue(handler.getSessions().containsKey("open-1"));
    }

    @Test
    @DisplayName("Exception during send to one client must not break broadcast to other clients")
    void testBroadcastContinuesOnClientFailure() throws IOException {
        WebSocketSession failingSession = mock(WebSocketSession.class);
        when(failingSession.getId()).thenReturn("fail-1");
        when(failingSession.isOpen()).thenReturn(true);
        when(failingSession.getAttributes()).thenReturn(new HashMap<>());
        doThrow(new IOException("Broken pipe")).when(failingSession).sendMessage(any(TextMessage.class));

        WebSocketSession healthySession = mock(WebSocketSession.class);
        when(healthySession.getId()).thenReturn("healthy-2");
        when(healthySession.isOpen()).thenReturn(true);
        when(healthySession.getAttributes()).thenReturn(new HashMap<>());

        handler.getSessions().put("fail-1", failingSession);
        handler.getSessions().put("healthy-2", healthySession);

        Notification notification = new Notification("n2", "Alert", "Critical disk", Severity.CRITICAL, Instant.now());
        handler.broadcastNotification(notification);

        // Healthy session received the message despite fail-1 throwing IOException
        verify(healthySession, times(1)).sendMessage(any(TextMessage.class));

        // Failing session is removed
        assertFalse(handler.getSessions().containsKey("fail-1"));
        assertTrue(handler.getSessions().containsKey("healthy-2"));
    }

    @Test
    @DisplayName("afterConnectionEstablished sends history and updates connection count")
    void testAfterConnectionEstablished() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-1");
        when(session.isOpen()).thenReturn(true);
        when(session.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
        Map<String, Object> attributes = new HashMap<>();
        when(session.getAttributes()).thenReturn(attributes);

        Notification pastNotif = new Notification("p1", "Past", "Historic notif", Severity.INFO, Instant.now());
        when(notificationStore.getRecent()).thenReturn(List.of(pastNotif));

        handler.afterConnectionEstablished(session);

        assertEquals(1, handler.getActiveSessionCount());
        // Verify history was sent (it sends at least 2 messages: history + connectionCount)
        verify(session, times(2)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("afterConnectionClosed removes session and broadcasts updated count")
    void testAfterConnectionClosed() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-2");
        handler.getSessions().put("sess-2", session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertEquals(0, handler.getActiveSessionCount());
        assertFalse(handler.getSessions().containsKey("sess-2"));
    }
}
