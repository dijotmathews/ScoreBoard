package com.dijo.ui;

import com.dijo.events.EventUpdates;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
import java.util.function.Predicate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Re-runs a view's reload (via server push) when an event changes. Updates arrive on
 * a background thread, so the reload runs as the user who opened the view, never as
 * whoever made the change. Call from onAttach; run the returned Runnable in onDetach.
 */
final class LiveReload {
    private LiveReload() {}

    static Runnable subscribe(UI ui, EventUpdates updates, Predicate<String> forEvent, Runnable reload) {
        SecurityContext viewer = SecurityContextHolder.createEmptyContext();
        viewer.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
        return updates.subscribe(eventId -> {
            if (!forEvent.test(eventId)) return;
            try {
                ui.access(() -> runAs(viewer, reload));
            } catch (UIDetachedException closed) {
                // The view was closed; its onDetach unsubscribes.
            }
        });
    }

    private static void runAs(SecurityContext context, Runnable action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(context);
        try {
            action.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
