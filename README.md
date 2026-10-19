# iRIS_Challenege — Real-Time Notification System with WebSockets

A production-grade, real-time notification broadcasting system built with **Java 21**, **Spring Boot 3.3.4**, and **Raw Spring WebSockets** (`TextWebSocketHandler` at `/ws`).

> 📖 **Comprehensive Architecture, Process Maps & Memory Model**: See [WORKFLOW_AND_MEMORY.md](WORKFLOW_AND_MEMORY.md) for sequence diagrams, process maps, and the in-memory bounds analysis.

The system includes:
- **Admin Dashboard** (`/admin.html`): Compose and broadcast notifications, inspect the live connected client counter, and view recent history.
- **Client Notification Receiver** (`/client.html`): Native WebSocket client rendering real-time animated toasts and stream items color-coded by severity, with automatic reconnect and exponential backoff.
- **REST API** (`POST /notify` and `GET /notifications`): Programmatic fallback and integration endpoint enforcing Bean Validation.
- **Portal Landing Hub** (`/` or `/index.html`): Quick launchpad and endpoint directory.

---

## Prerequisites
- **Java**: JDK 17 or higher (Java 21 recommended)
- **Maven**: Version 3.8+ (A `mvn.cmd` / `mvnw.cmd` wrapper is also included in the repository)

---

## How to Build and Run

### 1. Build and Run Unit Tests
```bash
mvn clean test
```
*(Or use `.\mvn.cmd clean test` / `.\mvnw.cmd clean test`)*

### 2. Start the Application
Run directly using the Spring Boot Maven plugin:
```bash
mvn spring-boot:run
```

Or package into an executable JAR and run:
```bash
mvn clean package -DskipTests
java -jar target/realtime-notifications-1.0.0.jar
```

The application will start on port **8080**:
```
Tomcat started on port 8080 (http) with context path '/'
Started NotificationApplication in 2.1 seconds
```

---

## Web Application URLs

Open your browser to:
- **Landing Hub**: [http://localhost:8080/](http://localhost:8080/)
- **Admin Dashboard**: [http://localhost:8080/admin.html](http://localhost:8080/admin.html)
- **Client Page**: [http://localhost:8080/client.html](http://localhost:8080/client.html)

> **Tip for Testing Real-Time Behavior:** Open the Admin page in one window and one or more Client pages in other tabs/windows side-by-side. You will see the connected clients counter increment live, and any notification sent from the form or via `curl` will pop up immediately as an animated toast on all client screens.

---

## REST API & Curl Examples

### 1. Broadcast a Notification (`POST /notify`)

#### Info Severity (Auto-dismisses in 5s):
```bash
curl -X POST http://localhost:8080/notify \
  -H "Content-Type: application/json" \
  -d '{
    "title": "System Update",
    "message": "Routine maintenance completed successfully.",
    "severity": "info"
  }'
```

#### Warning Severity (Auto-dismisses in 7s):
```bash
curl -X POST http://localhost:8080/notify \
  -H "Content-Type: application/json" \
  -d '{
    "title": "High Memory Usage",
    "message": "Memory utilization crossed 85% on node worker-3.",
    "severity": "warning"
  }'
```

#### Critical Severity (Stays open until user dismisses):
```bash
curl -X POST http://localhost:8080/notify \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Database Outage",
    "message": "Primary database replica is unresponsive. Failover initiated.",
    "severity": "critical"
  }'
```

**Success Response (`201 Created`):**
```json
{
  "id": "e8a9462c-8bb2-4752-9b57-657d2a58b5be",
  "title": "Database Outage",
  "message": "Primary database replica is unresponsive. Failover initiated.",
  "severity": "critical",
  "timestamp": "2026-10-19T08:06:47.212372Z"
}
```

---

### 2. Validation Errors (`400 Bad Request`)

#### Missing or Blank Title:
```bash
curl -X POST http://localhost:8080/notify \
  -H "Content-Type: application/json" \
  -d '{
    "title": "",
    "message": "Some message",
    "severity": "info"
  }'
```
**Response:**
```json
{
  "timestamp": "2026-10-19T08:05:11.378025Z",
  "status": 400,
  "error": "Validation Failed",
  "message": "One or more input fields failed validation constraints.",
  "fieldErrors": {
    "title": "Title is required and cannot be blank"
  }
}
```

#### Invalid Severity Value:
```bash
curl -X POST http://localhost:8080/notify \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Alert",
    "message": "Message body",
    "severity": "fatal"
  }'
```
**Response:**
```json
{
  "timestamp": "2026-10-19T08:05:21.959062Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid severity value. Allowed values are: info, warning, critical."
}
```

---

### 3. Retrieve Recent Notifications (`GET /notifications`)
Fetches the last 10 notifications stored in memory, ordered **newest first**:
```bash
curl http://localhost:8080/notifications
```

---

### 4. Query Live Connected Clients (`GET /connections/count`)
```bash
curl http://localhost:8080/connections/count
```
**Response:**
```json
{
  "count": 2
}
```

---

## Architecture and Design Decisions

```
+-------------------------------------------------------------+
|                      Client Layer                           |
|   +-----------------------+     +-----------------------+   |
|   |   Admin Dashboard     |     |   Client Page(s)      |   |
|   |  (admin.html + JS)    |     |  (client.html + JS)   |   |
|   +-----------+-----------+     +-----------+-----------+   |
+---------------|-----------------------------|---------------+
                |                             |
      POST /notify (fetch)              WebSocket /ws
                |                             |
+---------------v-----------------------------v---------------+
|                   Spring Boot Application                   |
|                                                             |
|   [NotificationController]                                  |
|              |                                              |
|              v (Single Entry Path)                          |
|   [NotificationService.broadcast()]                         |
|         |                     |                             |
|         v                     v                             |
|  [NotificationStore]   [NotificationWebSocketHandler]       |
|  - In-memory deque     - ConcurrentHashMap sessions         |
|  - Capped at 10        - ConcurrentWebSocketSessionDecorator|
|  - Newest-first        - 30s Heartbeat Ping/Pong            |
|                        - Broadcasts to all open sessions    |
+-------------------------------------------------------------+
```

### Key Architectural Choices:
1. **Single Broadcast Path**: Both the REST endpoint (`POST /notify`) and the Admin Dashboard form route through a single unified method: `NotificationService.broadcast()`. This ensures that validation, UUID generation, ISO-8601 timestamping, history persistence, and WebSocket broadcasting always follow an identical, centralized path.
2. **WebSocket Thread Safety**: `WebSocketSession.sendMessage` is not inherently thread-safe. To prevent race conditions during concurrent broadcasts, all sessions are wrapped in Spring's `ConcurrentWebSocketSessionDecorator` with buffer and send-time limits (10s, 64KB).
3. **Resilient Broadcast Loop**: Broadcasting only targets sessions where `isOpen() == true`. If transmitting to one client fails (e.g. abrupt disconnect or broken pipe), that session is safely closed and removed without interrupting delivery to the remaining clients.
4. **Bounded In-Memory History**: `NotificationStore` uses a synchronized `ArrayDeque` capped at `MAX_CAPACITY = 10`. New notifications are prepended (`addFirst`) and any excess tail items are evicted (`removeLast`), ensuring constant memory footprint and consistent newest-first ordering.
5. **Heartbeat & Zombie Session Pruning**: An automated `@Scheduled(fixedDelay = 30000)` task pings every active session and evicts dead sessions that missed pong responses for over 65 seconds, keeping the live client count strictly accurate.
6. **Client-Side Exponential Backoff**: The frontend uses native `WebSocket` with automatic reconnection backoff starting at 1s, doubling up to 30s (`1s -> 2s -> 4s -> 8s -> 16s -> 30s`). The backoff counter resets immediately upon a successful connection.
7. **Strict XSS Prevention**: All user-supplied text (`title`, `message`, `severity`) is safely injected into DOM nodes using `textContent` and `document.createElement()`, never `innerHTML`.

---

## Edge Cases Handled

1. **Abrupt Client Disconnection / Network Drop**:
   - `afterConnectionClosed` and `handleTransportError` remove the session and immediately broadcast the updated client count to remaining sessions.
   - 30s heartbeat ping/pong prunes half-open TCP connections.
2. **Reconnecting Client Blindness**:
   - When a client connects or reconnects, the server immediately sends a `{ type: "history", notifications: [...] }` payload containing the last 10 notifications before broadcasting the updated connection count.
3. **Slow or Broken WebSocket Consumers**:
   - `ConcurrentWebSocketSessionDecorator` prevents slow consumers from locking the broadcast thread or causing memory bloat by buffering and closing stalled sessions.
4. **Invalid Enum Values & Malformed JSON**:
   - Case-insensitive deserializer (`info`, `INFO`, `Warning`, `CRITICAL`) handles varied client input.
   - `GlobalExceptionHandler` intercepts bad values and formats a clear error message: `"Invalid severity value. Allowed values are: info, warning, critical."` with HTTP 400.
5. **XSS & Injection Protection**:
   - Script tags or HTML entities in title/message are rendered as plain text safely in the DOM.
6. **Concurrent REST & WebSocket Access**:
   - Thread-safe collections (`ConcurrentHashMap`, synchronized `ArrayDeque`) guarantee integrity under heavy load.
