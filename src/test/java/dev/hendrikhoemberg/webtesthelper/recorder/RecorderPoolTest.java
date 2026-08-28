package dev.hendrikhoemberg.webtesthelper.recorder;

import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("browser")
class RecorderPoolTest {

    private static FixtureSite site;

    @BeforeAll
    static void start() {
        site = FixtureSite.start();
    }

    @AfterAll
    static void stop() {
        site.close();
    }

    private static RecorderProperties properties(int maxSessions) {
        return new RecorderProperties(maxSessions, Duration.ofMinutes(15), 60, 1280, 720, true);
    }

    @Test
    void browserWorkNeverRunsOnTheCallingThread() throws Exception {
        try (RecorderPool pool = new RecorderPool(properties(1))) {
            RecorderWorker worker = pool.allocate().orElseThrow();
            try {
                String caller = Thread.currentThread().getName();
                String inside = worker.submit(browser -> Thread.currentThread().getName());
                assertThat(inside).startsWith("recorder-worker-").isNotEqualTo(caller);
            } finally {
                pool.release(worker);
            }
        }
    }

    @Test
    void everyTaskOfOneWorkerRunsOnThatWorkersSingleThread() throws Exception {
        try (RecorderPool pool = new RecorderPool(properties(1))) {
            RecorderWorker worker = pool.allocate().orElseThrow();
            try {
                List<String> threads = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    threads.add(worker.submit(browser -> Thread.currentThread().getName()));
                }
                assertThat(threads).hasSize(5).containsOnly(threads.getFirst());
            } finally {
                pool.release(worker);
            }
        }
    }

    @Test
    void allocationsAreBoundedAndNonBlocking() throws Exception {
        try (RecorderPool pool = new RecorderPool(properties(2))) {
            assertThat(pool.size()).isEqualTo(2);
            assertThat(pool.busy()).isEqualTo(0);

            Optional<RecorderWorker> first = pool.allocate();
            Optional<RecorderWorker> second = pool.allocate();
            Optional<RecorderWorker> third = pool.allocate();

            assertThat(first).isPresent();
            assertThat(second).isPresent();
            assertThat(third).isEmpty();
            assertThat(pool.busy()).isEqualTo(2);

            pool.release(first.get());
            pool.release(second.get());
        }
    }

    @Test
    void releasingAWorkerMakesItAvailableAgain() throws Exception {
        try (RecorderPool pool = new RecorderPool(properties(2))) {
            RecorderWorker first = pool.allocate().orElseThrow();
            RecorderWorker second = pool.allocate().orElseThrow();
            assertThat(pool.allocate()).isEmpty();

            pool.release(first);
            assertThat(pool.busy()).isEqualTo(1);

            Optional<RecorderWorker> third = pool.allocate();
            assertThat(third).isPresent();
            assertThat(pool.busy()).isEqualTo(2);

            String title = third.get().submit(browser -> {
                try (var context = browser.newContext()) {
                    var page = context.newPage();
                    page.navigate(site.baseUrl());
                    return page.title();
                }
            });
            assertThat(title).contains("Startseite");

            pool.release(second);
            pool.release(third.get());
            assertThat(pool.busy()).isEqualTo(0);
        }
    }

    @Test
    void releasingAnUnallocatedOrNullWorkerDoesNotCorruptPool() throws Exception {
        try (RecorderPool pool = new RecorderPool(properties(2))) {
            pool.release(null);
            assertThat(pool.busy()).isEqualTo(0);

            RecorderWorker first = pool.allocate().orElseThrow();
            pool.release(first);
            // Duplicate release should not inflate available workers
            pool.release(first);

            Optional<RecorderWorker> a = pool.allocate();
            Optional<RecorderWorker> b = pool.allocate();
            Optional<RecorderWorker> c = pool.allocate();

            assertThat(a).isPresent();
            assertThat(b).isPresent();
            assertThat(c).isEmpty();

            pool.release(a.get());
            pool.release(b.get());
        }
    }

    @Test
    void aWorkerWhoseBrowserDiedRestartsItRatherThanFailingEveryLaterTask() throws Exception {
        try (RecorderPool pool = new RecorderPool(properties(1))) {
            RecorderWorker worker = pool.allocate().orElseThrow();
            try {
                worker.submit(browser -> {
                    browser.close();
                    return null;
                });
                String title = worker.submit(browser -> {
                    try (var context = browser.newContext()) {
                        var page = context.newPage();
                        page.navigate(site.baseUrl());
                        return page.title();
                    }
                });
                assertThat(title).contains("Startseite");
            } finally {
                pool.release(worker);
            }
        }
    }

    @Test
    void aFailingTaskPropagatesItsCauseAndLeavesTheWorkerUsable() throws Exception {
        try (RecorderPool pool = new RecorderPool(properties(1))) {
            RecorderWorker worker = pool.allocate().orElseThrow();
            try {
                assertThatThrownBy(() ->
                        worker.submit(browser -> { throw new IllegalStateException("kaputt"); }))
                        .hasRootCauseMessage("kaputt");
                assertThat(worker.<Boolean>submit(browser -> browser.isConnected())).isTrue();
            } finally {
                pool.release(worker);
            }
        }
    }

    @Test
    void theCallersLoggingContextTravelsToTheWorkerThread() throws Exception {
        try (RecorderPool pool = new RecorderPool(properties(1))) {
            RecorderWorker worker = pool.allocate().orElseThrow();
            try {
                MDC.put("sessionId", "test-session-123");
                MDC.put("username", "hendrik");
                try {
                    String sessionId = worker.submit(browser -> MDC.get("sessionId"));
                    String username = worker.submit(browser -> MDC.get("username"));
                    assertThat(sessionId).isEqualTo("test-session-123");
                    assertThat(username).isEqualTo("hendrik");
                } finally {
                    MDC.clear();
                }
            } finally {
                pool.release(worker);
            }
        }
    }

    @Test
    void aWorkerThreadDoesNotKeepThePreviousTasksLoggingContext() throws Exception {
        try (RecorderPool pool = new RecorderPool(properties(1))) {
            RecorderWorker worker = pool.allocate().orElseThrow();
            try {
                MDC.put("sessionId", "test-session-123");
                try {
                    worker.submit(browser -> MDC.get("sessionId"));
                } finally {
                    MDC.clear();
                }
                assertThat(worker.<String>submit(browser -> MDC.get("sessionId"))).isNull();
            } finally {
                pool.release(worker);
            }
        }
    }
}
