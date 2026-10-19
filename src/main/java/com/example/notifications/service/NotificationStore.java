package com.example.notifications.service;

import com.example.notifications.model.Notification;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Thread-safe, in-memory store capped at the last 10 notifications.
 *
 * Ordering Contract:
 * - Notifications are stored and returned in NEWEST-FIRST order (index 0 is the most recent notification).
 */
@Component
public class NotificationStore {

    public static final int MAX_CAPACITY = 10;

    private final Deque<Notification> deque = new ArrayDeque<>(MAX_CAPACITY);

    /**
     * Adds a new notification to the store.
     * Inserts at the front (newest first). If the store exceeds MAX_CAPACITY (10),
     * the oldest notification at the tail is evicted.
     *
     * @param notification the notification to add
     */
    public synchronized void add(Notification notification) {
        if (notification == null) {
            return;
        }
        if (deque.size() >= MAX_CAPACITY) {
            deque.removeLast();
        }
        deque.addFirst(notification);
    }

    /**
     * Retrieves an unmodifiable snapshot of the stored notifications.
     *
     * @return List of notifications in newest-first order, capped at 10 items.
     */
    public synchronized List<Notification> getRecent() {
        return Collections.unmodifiableList(new ArrayList<>(deque));
    }

    /**
     * Returns the current number of stored notifications.
     */
    public synchronized int size() {
        return deque.size();
    }

    /**
     * Clears all stored notifications. Primarily for unit testing.
     */
    public synchronized void clear() {
        deque.clear();
    }
}
