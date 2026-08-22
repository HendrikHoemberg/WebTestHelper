package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
class BrowserPoolTest {

    private static FixtureSite site;

    @BeforeAll static void start() { site = FixtureSite.start(); }
    @AfterAll static void stop() { site.close(); }

    private static CrawlerProperties properties(int workers) {
        return new CrawlerProperties(workers, 20, Duration.ofSeconds(15), Duration.ZERO,
                Path.of(System.getProperty("java.io.tmpdir"), "wth-pool-test"), true);
    }

    @Test
    void browserWorkNeverRunsOnTheCallingThread() throws Exception {
        try (BrowserPool pool = new BrowserPool(properties(1))) {
            String caller = Thread.currentThread().getName();
            String inside = pool.submit(browser -> Thread.currentThread().getName());
            assertThat(inside).startsWith("browser-worker-").isNotEqualTo(caller);
        }
    }

    @Test
    void everyTaskOfOneWorkerRunsOnThatWorkersSingleThread() throws Exception {
        try (BrowserPool pool = new BrowserPool(properties(1))) {
            List<String> threads = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                threads.add(pool.submit(browser -> Thread.currentThread().getName()));
            }
            assertThat(threads).hasSize(5).containsOnly(threads.getFirst());
        }
    }

    @Test
    void concurrentCallersAreBoundedByThePoolSize() throws Exception {
        try (BrowserPool pool = new BrowserPool(properties(2));
             ExecutorService callers = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> work = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                work.add(() -> pool.submit(browser -> {
                    try (var context = browser.newContext()) {
                        var page = context.newPage();
                        page.navigate(site.baseUrl());
                        return page.title();
                    }
                }));
            }
            List<String> titles = new java.util.ArrayList<>();
            for (Future<String> future : callers.invokeAll(work)) {
                titles.add(future.get());
            }
            assertThat(titles).hasSize(8).allSatisfy(title ->
                    assertThat(title).contains("Startseite"));
            assertThat(pool.size()).isEqualTo(2);
        }
    }

    @Test
    void aWorkerWhoseBrowserDiedRestartsItRatherThanFailingEveryLaterTask() throws Exception {
        // Spec 14: "if the Browser dies, it is restarted and the run resumes".
        try (BrowserPool pool = new BrowserPool(properties(1))) {
            pool.submit(browser -> {
                browser.close();
                return null;
            });
            String title = pool.submit(browser -> {
                try (var context = browser.newContext()) {
                    var page = context.newPage();
                    page.navigate(site.baseUrl());
                    return page.title();
                }
            });
            assertThat(title).contains("Startseite");
        }
    }

    @Test
    void aFailingTaskPropagatesItsCauseAndLeavesTheWorkerUsable() throws Exception {
        try (BrowserPool pool = new BrowserPool(properties(1))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            pool.submit(browser -> { throw new IllegalStateException("kaputt"); }))
                    .hasRootCauseMessage("kaputt");
            assertThat(pool.<Boolean>submit(browser -> browser.isConnected())).isTrue();
        }
    }
}