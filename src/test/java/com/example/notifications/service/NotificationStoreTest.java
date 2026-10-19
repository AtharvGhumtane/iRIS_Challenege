package com.example.notifications.service;

import com.example.notifications.model.Notification;
import com.example.notifications.model.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationStoreTest {

    private NotificationStore store;

    @BeforeEach
    void setUp() {
        store = new NotificationStore();
    }

    @Test
    @DisplayName("Should cap at 10 items and evict oldest notifications (newest first)")
    void testStoreCappingAtTen() {
        // Add 15 notifications sequentially
        for (int i = 1; i <= 15; i++) {
            store.add(Notification.of("Title " + i, "Message " + i, Severity.INFO));
        }

        List<Notification> recent = store.getRecent();

        // Must cap at 10
        assertEquals(10, recent.size(), "Store must be capped at 10 items");

        // The first item must be the newest (Title 15)
        assertEquals("Title 15", recent.get(0).title(), "Most recent notification must be at index 0");

        // The 10th item must be Title 6 (Title 1 through 5 should have been evicted)
        assertEquals("Title 6", recent.get(9).title(), "Oldest retained notification must be Title 6");
    }

    @Test
    @DisplayName("Should maintain thread safety under concurrent writes")
    void testConcurrentWrites() throws InterruptedException {
        int threadCount = 20;
        int itemsPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < itemsPerThread; i++) {
                        store.add(Notification.of("T" + threadId + "-I" + i, "Msg", Severity.INFO));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        List<Notification> recent = store.getRecent();
        assertEquals(10, recent.size(), "Size must strictly equal 10 under concurrent access");
        assertFalse(recent.isEmpty());
    }

    @Test
    @DisplayName("Should safely ignore null additions")
    void testNullAdditionIgnored() {
        store.add(null);
        assertEquals(0, store.size());
        assertTrue(store.getRecent().isEmpty());
    }
}
