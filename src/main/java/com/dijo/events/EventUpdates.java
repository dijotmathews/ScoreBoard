package com.dijo.events;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells subscribers (such as open player screens) that an event, its draw or its
 * matches changed. Subscribers are called after the change commits, on a separate
 * thread, never on the thread of the user who made the change.
 */
@Component
public class EventUpdates implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(EventUpdates.class);

    record Changed(String eventId) {}

    private final ApplicationEventPublisher publisher;
    private final Set<Consumer<String>> subscribers = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public EventUpdates(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** Called by services inside the transaction that changes the event. */
    void publish(String eventId) {
        publisher.publishEvent(new Changed(eventId));
    }

    /** Subscribes to changed event IDs; run the returned Runnable to unsubscribe. */
    public Runnable subscribe(Consumer<String> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    @TransactionalEventListener(fallbackExecution = true)
    void afterCommit(Changed changed) {
        for (Consumer<String> subscriber : subscribers) {
            executor.execute(() -> {
                try {
                    subscriber.accept(changed.eventId());
                } catch (RuntimeException ex) {
                    log.warn("Event update subscriber failed", ex);
                }
            });
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
