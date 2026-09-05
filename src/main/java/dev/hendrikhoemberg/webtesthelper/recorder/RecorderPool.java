package dev.hendrikhoemberg.webtesthelper.recorder;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A dedicated pool of browser workers for interactive recording sessions (spec 10.1, D109).
 *
 * <p>Unlike crawl pools (which queue tasks in a blocking queue
 * for short crawls), {@code RecorderPool} provides non-blocking allocation with a hard cap (max 2 by default).
 * If all workers are busy, {@link #allocate()} immediately returns {@link Optional#empty()} so the user
 * can be told about capacity limits rather than hanging.
 */
@Component
public class RecorderPool implements AutoCloseable {

    private final List<RecorderWorker> workers;
    private final Deque<RecorderWorker> available = new ArrayDeque<>();
    private final Set<RecorderWorker> allocated = new HashSet<>();
    private boolean closed = false;

    public RecorderPool(RecorderProperties properties) {
        int size = Math.max(1, properties.maxSessions());
        List<RecorderWorker> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            RecorderWorker worker = new RecorderWorker(i, properties.headless(), properties.noSandbox());
            list.add(worker);
            available.add(worker);
        }
        this.workers = Collections.unmodifiableList(list);
    }

    /**
     * Total configured worker capacity.
     */
    public int size() {
        return workers.size();
    }

    /**
     * Workers currently allocated to recording sessions.
     */
    public synchronized int busy() {
        return allocated.size();
    }

    /**
     * Attempts to allocate a worker for a recording session.
     *
     * @return an {@link Optional} containing a worker if available, or {@link Optional#empty()} if at capacity
     */
    public synchronized Optional<RecorderWorker> allocate() {
        if (closed || available.isEmpty()) {
            return Optional.empty();
        }
        RecorderWorker worker = available.removeFirst();
        allocated.add(worker);
        return Optional.of(worker);
    }

    /**
     * Returns a previously allocated worker to the pool.
     */
    public synchronized void release(RecorderWorker worker) {
        if (worker == null || closed) {
            return;
        }
        if (allocated.remove(worker)) {
            available.addLast(worker);
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        close(5, TimeUnit.SECONDS);
    }

    synchronized void close(long timeout, TimeUnit unit) {
        if (closed) {
            return;
        }
        closed = true;
        allocated.clear();
        available.clear();
        workers.forEach(w -> w.close(timeout, unit));
    }
}
