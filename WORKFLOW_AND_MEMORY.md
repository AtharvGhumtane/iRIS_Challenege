# System Workflow, Process Map & In-Memory Storage Architecture

This document provides a detailed breakdown of the internal mechanics, execution flows, and memory management strategy of the Real-Time Notification System.

---

## 1. System Architecture & High-Level Workflow

The system connects web browser clients and external services to a single Spring Boot application hosting both raw WebSocket endpoints and REST APIs.

```mermaid
graph TD
    subgraph Clients["Client Layer"]
        A["Admin Dashboard"]
        C1["Client 1 - Receiver"]
        C2["Client 2 - Receiver"]
        EXT["External Integration / cURL"]
    end

    subgraph SpringBoot["Spring Boot Backend - Port 8080"]
        WS_CFG["WebSocketConfig at /ws"]
        CTRL["NotificationController - REST Endpoints"]
        
        SVC["NotificationService.broadcast - Single Entry Point"]
        
        subgraph Storage["In-Memory Bounded Store"]
            STORE["NotificationStore - ArrayDeque Capped at 10"]
        end
        
        subgraph WS_Engine["WebSocket Management Engine"]
            HANDLER["NotificationWebSocketHandler"]
            SESSIONS["ConcurrentHashMap - Active Sessions"]
            HEARTBEAT["Scheduled Heartbeat - 30s Ping Sweep"]
        end
    end

    %% Interactions
    A -->|1. Submit Form via POST /notify| CTRL
    EXT -->|1. HTTP POST /notify| CTRL
    CTRL -->|2. Delegate Request| SVC
    SVC -->|3. Save Recent - Capped at 10| STORE
    SVC -->|4. Push Broadcast Event| HANDLER
    HANDLER -->|5. Non-blocking Safe Push| SESSIONS
    
    C1 <-->|WebSocket Handshake at /ws| WS_CFG
    C2 <-->|WebSocket Handshake at /ws| WS_CFG
    A <-->|WebSocket Handshake at /ws| WS_CFG
    WS_CFG --> HANDLER
    
    SESSIONS -.->|Live Notification Toasts| C1
    SESSIONS -.->|Live Notification Toasts| C2
    SESSIONS -.->|Live Connection Counter| A
    HEARTBEAT -.->|Ping and Pong Evaluation| SESSIONS
```

---

## 2. Process Maps

### Process Map 1: Client Connection & Handshake Lifecycle
When an admin or client loads their page in the browser:

```mermaid
sequenceDiagram
    autonumber
    participant Browser as Client Browser
    participant Config as WebSocketConfig
    participant Handler as NotificationWebSocketHandler
    participant Store as NotificationStore
    participant Map as Session Map

    Browser->>Config: WS Connect to /ws
    Config->>Handler: afterConnectionEstablished
    Handler->>Handler: Decorate session with buffer limits
    Handler->>Handler: Record initial ping timestamp
    Handler->>Map: Register session in map
    Handler->>Store: getRecent
    Store-->>Handler: Return snapshot of last 10 notifications
    Handler-->>Browser: Send history frame with recent notifications
    Handler->>Handler: Calculate active session count
    Handler-->>Browser: Broadcast connectionCount frame to all clients
    Note over Browser: Reset backoff to 1s and render initial stream
```

---

### Process Map 2: Unified Broadcast Pipeline
Both the Admin Web Form and `POST /notify` follow the identical code path:

```mermaid
sequenceDiagram
    autonumber
    participant AdminUI as Admin Form or cURL
    participant Controller as NotificationController
    participant Service as NotificationService
    participant Store as NotificationStore
    participant Handler as NotificationWebSocketHandler
    participant ClientUI as Connected Clients

    AdminUI->>Controller: POST /notify with JSON payload
    Note over Controller: Execute Bean Validation checks
    alt Invalid Input
        Controller-->>AdminUI: 400 Bad Request with fieldErrors JSON
    else Valid Input
        Controller->>Service: broadcast NotifyRequest
        Service->>Service: Create Notification record with UUID and timestamp
        Service->>Store: add notification
        Note over Store: Synchronized addFirst - evicts oldest if size exceeds 10
        Service->>Handler: broadcastNotification
        loop For each WebSocketSession in Map
            alt Session is Open
                Handler->>ClientUI: Send notification JSON frame
            else Session is Closed or Broken
                Handler->>Handler: Remove session from Map and close safely
            end
        end
        Service-->>Controller: Return created Notification record
        Controller-->>AdminUI: 201 Created JSON Response
    end
    Note over ClientUI: Safe DOM insertion via textContent and animate toast
```

---

### Process Map 3: Heartbeat, Timeout & Disconnect Process
Ensures the server never retains zombie sessions or dead sockets:

```mermaid
sequenceDiagram
    autonumber
    participant Scheduler as Spring Scheduled Heartbeat
    participant Handler as NotificationWebSocketHandler
    participant Map as Session Map
    participant Client as Connected Client

    loop Every 30 Seconds
        Scheduler->>Handler: heartbeat sweep
        loop Check each active session
            alt session is closed
                Handler->>Map: Remove session
            else no pong response for over 65 seconds
                Handler->>Client: Close session due to timeout
                Handler->>Map: Remove timed-out session
            else Session Healthy
                Handler->>Client: Send WebSocket PingFrame
                Client-->>Handler: Return PongFrame
                Handler->>Handler: Update last pong timestamp
            end
        end
        alt Any session was removed
            Handler->>Client: Broadcast updated connectionCount
        end
    end
```

---

### Process Map 4: Client-Side Reconnect with Exponential Backoff
When the backend restarts or network drops temporarily:

```mermaid
flowchart TD
    Start["WebSocket Connection Closed"] --> SetStatus["UI updates: Reconnecting in Xs..."]
    SetStatus --> Wait["Wait current delay: 1s, 2s, 4s, 8s, 16s, 30s"]
    Wait --> Attempt["Attempt new WebSocket connection"]
    Attempt --> Check{"Connection Succeeded?"}
    Check -->|Yes| Connected["Status: Connected - Reset delay to 1s"]
    Check -->|No| DoubleDelay["Double delay: min delay*2, max 30s"]
    DoubleDelay --> SetStatus
```

---

## 3. How Memory is Saved & Managed

### 1. In-Memory Ring Buffer / Capped Deque
Instead of relying on a database or an unbounded `List` that grows indefinitely and eventually causes `OutOfMemoryError` (OOM), the application implements a strict **bounded queue** in [`NotificationStore.java`](file:///c:/Users/athar/Desktop/iRIS/src/main/java/com/example/notifications/service/NotificationStore.java):

```java
public class NotificationStore {
    public static final int MAX_CAPACITY = 10;
    private final Deque<Notification> deque = new ArrayDeque<>(MAX_CAPACITY);

    public synchronized void add(Notification notification) {
        if (notification == null) return;
        if (deque.size() >= MAX_CAPACITY) {
            deque.removeLast(); // Constant-time O(1) eviction of the oldest item
        }
        deque.addFirst(notification); // Constant-time O(1) insertion of newest item
    }

    public synchronized List<Notification> getRecent() {
        return Collections.unmodifiableList(new ArrayList<>(deque));
    }
}
```

#### Why `ArrayDeque`?
- **Zero Pointer Overhead**: Unlike `LinkedList`, which allocates a `Node` object with two reference pointers (`prev` and `next`) for every stored entry, `ArrayDeque` is backed by a circular array.
- **Fixed Memory Footprint**: The capacity is capped at 10. Memory consumption never exceeds $\approx 10 \times \text{size of Notification}$.
- **Garbage Collection (GC) Friendliness**: Evicted objects immediately become unreachable and are collected in the Young Generation (Eden/Survivor spaces) without promoting to the Tenured/Old generation.

### 2. Memory Bound Calculation

| Component | Maximum Elements | Approximate Size per Element | Total Memory Overhead |
| :--- | :--- | :--- | :--- |
| `Notification` Record | 10 | ~256 bytes (UUID + Title [max 100] + Message [max 500] + Instant) | **< 4 KB** |
| `ArrayDeque` Array Reference | 16 elements (internal power of 2) | 8 bytes per reference | **~128 bytes** |
| `sessions` (`ConcurrentHashMap`) | 1 per active client | ~128 bytes entry + session descriptor | Scales strictly $O(N)$ with active users |

**Result:** The core notification store consumes less than **5 Kilobytes** of RAM regardless of whether the server runs for 10 minutes or 10 months.

---

### 3. Preventing Memory Leaks from Dead WebSocket Sockets
In raw WebSockets, unhandled socket disconnections or half-open connections can hold file descriptors and memory buffers permanently. The system prevents this through three defensive layers:

1. **`ConcurrentWebSocketSessionDecorator`**:
   - Caps outgoing message buffer to `64 KB`.
   - Sets a hard limit on send execution time (`10,000 ms`).
   - If a client stalls, Spring drops buffered messages rather than buffering endlessly in memory.
2. **Dual-Hook Cleanups**:
   - Sessions are cleared from memory in both `afterConnectionClosed` and `handleTransportError`.
3. **Heartbeat Sweep**:
   - Every 30 seconds, `heartbeat()` prunes non-responsive sessions, ensuring the `sessions` map never retains references to unreachable clients.

---

### 4. DOM Memory Management (Client Side)
On the browser side:
- **Admin Page**: The recent history list in the DOM is hard-capped to 10 elements via `while (historyList.children.length > 10) historyList.removeChild(historyList.lastChild);`.
- **Client Page Toasts**: Auto-dismiss timers (5s for Info, 7s for Warning) remove the toast DOM nodes and clear internal timer handles to keep the browser lightweight and responsive.
