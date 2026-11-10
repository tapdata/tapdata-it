package io.tapdata.it.l3.core.cleanup;

import io.tapdata.it.l3.core.console.ConsoleUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * LIFO cleanup manager — resources are released in reverse-allocation order.
 * <p>
 * Steps that allocate external resources (dbforge leases, TM datasources, etc.)
 * call {@link #register(Runnable)} with their cleanup action.
 * When the scenario finishes (success or failure), {@link #triggerTeardown()}
 * executes all registered actions in reverse order, swallowing individual failures.
 */
@Component
public class CleanupManager {

    private static final Logger log = LoggerFactory.getLogger(CleanupManager.class);

    private final Deque<Runnable> stack = new ArrayDeque<>();

    public synchronized void register(Runnable cleanupAction) {
        stack.push(cleanupAction);
        log.debug("[Cleanup] Registered action — stack size now {}", stack.size());
    }

    /** Execute all registered cleanup actions in reverse order (LIFO). */
    public synchronized void triggerTeardown() {
        if (stack.isEmpty()) {
            log.debug("[Cleanup] No actions registered — nothing to tear down");
            return;
        }
        int total = stack.size();
        ConsoleUI.event("cleanup", total + " resource(s)");
        int failures = 0;
        while (!stack.isEmpty()) {
            Runnable action = stack.pop();
            try {
                action.run();
            } catch (Throwable t) {
                failures++;
                ConsoleUI.warn("cleanup failed: " + t.getMessage());
                log.debug("[Cleanup] Cleanup action failed", t);
            }
        }
        ConsoleUI.event("cleanup", failures == 0
                ? "done"
                : "done (" + failures + "/" + total + " failed)");
    }

    public synchronized int size() {
        return stack.size();
    }
}
