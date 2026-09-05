package dev.hendrikhoemberg.webtesthelper.recorder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordingSessionRegistryTest {

    private RecorderPool pool;
    private RecorderProperties properties;
    private TestClock clock;
    private RecordingSessionRegistry registry;

    @BeforeEach
    void setup() {
        pool = mock(RecorderPool.class);
        properties = new RecorderProperties(2, Duration.ofMinutes(15), 60, 1280, 720, true, false, Duration.ofMillis(100));
        clock = new TestClock(Instant.parse("2026-08-28T10:00:00Z"));
        registry = new RecordingSessionRegistry(pool, properties, clock);
    }

    @Test
    void sessionIsIdentifiedByRandomUuidNotSequentialId() {
        RecorderWorker worker1 = mock(RecorderWorker.class);
        RecorderWorker worker2 = mock(RecorderWorker.class);
        when(pool.allocate()).thenReturn(Optional.of(worker1), Optional.of(worker2));

        RecordingSession session1 = registry.open(1L, "https://example.com/a", "alice");
        RecordingSession session2 = registry.open(1L, "https://example.com/b", "alice");

        assertThat(session1.sessionId()).isNotNull();
        assertThat(session2.sessionId()).isNotNull();
        assertThat(session1.sessionId()).isNotEqualTo(session2.sessionId());
        assertThat(session1.siteId()).isEqualTo(1L);
        assertThat(session1.startUrl()).isEqualTo("https://example.com/a");
        assertThat(session1.username()).isEqualTo("alice");
        assertThat(session1.worker()).isSameAs(worker1);
    }

    @Test
    void findWithCorrectOwnerReturnsSession() {
        RecorderWorker worker = mock(RecorderWorker.class);
        when(pool.allocate()).thenReturn(Optional.of(worker));

        RecordingSession session = registry.open(1L, "https://example.com/start", "alice");

        Optional<RecordingSession> found = registry.find(session.sessionId(), "alice");
        assertThat(found).isPresent().contains(session);
    }

    @Test
    void findWithWrongOwnerReturnsEmpty() {
        RecorderWorker worker = mock(RecorderWorker.class);
        when(pool.allocate()).thenReturn(Optional.of(worker));

        RecordingSession session = registry.open(1L, "https://example.com/start", "alice");

        Optional<RecordingSession> found = registry.find(session.sessionId(), "bob");
        assertThat(found).isEmpty();
    }

    @Test
    void findWithUnknownSessionIdReturnsEmpty() {
        Optional<RecordingSession> found = registry.find(UUID.randomUUID(), "alice");
        assertThat(found).isEmpty();
    }

    @Test
    void findWithNullSessionIdOrUsernameReturnsEmpty() {
        RecorderWorker worker = mock(RecorderWorker.class);
        when(pool.allocate()).thenReturn(Optional.of(worker));

        RecordingSession session = registry.open(1L, "https://example.com/start", "alice");

        assertThat(registry.find(null, "alice")).isEmpty();
        assertThat(registry.find(session.sessionId(), null)).isEmpty();
    }

    @Test
    void explicitCloseReleasesWorkerAndRemovesSessionFromRegistry() {
        RecorderWorker worker = mock(RecorderWorker.class);
        when(pool.allocate()).thenReturn(Optional.of(worker));

        RecordingSession session = registry.open(1L, "https://example.com/start", "alice");
        UUID sessionId = session.sessionId();

        registry.close(sessionId);

        assertThat(registry.find(sessionId, "alice")).isEmpty();
        verify(pool).release(worker);
    }

    @Test
    void closingUnknownSessionOrNullDoesNothing() {
        registry.close(UUID.randomUUID());
        registry.close(null);
        verify(pool, never()).release(any());
    }

    @Test
    void activityClockIsStampedAtCreationAndUpdatedOnRecordActivity() {
        RecorderWorker worker = mock(RecorderWorker.class);
        when(pool.allocate()).thenReturn(Optional.of(worker));

        RecordingSession session = registry.open(1L, "https://example.com/start", "alice");
        assertThat(session.lastActivity()).isEqualTo(Instant.parse("2026-08-28T10:00:00Z"));

        clock.advance(Duration.ofMinutes(5));
        session.recordActivity();
        assertThat(session.lastActivity()).isEqualTo(Instant.parse("2026-08-28T10:05:00Z"));

        Instant explicit = Instant.parse("2026-08-28T10:08:00Z");
        session.recordActivity(explicit);
        assertThat(session.lastActivity()).isEqualTo(explicit);
    }

    @Test
    void idleSessionPastTimeoutIsReapedAndWorkerReturnedToPool() {
        RecorderWorker worker = mock(RecorderWorker.class);
        when(pool.allocate()).thenReturn(Optional.of(worker));

        RecordingSession session = registry.open(1L, "https://example.com/start", "alice");
        UUID sessionId = session.sessionId();

        // Advance 15m 1s past the 15m idle timeout
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        registry.reapIdle();

        assertThat(registry.find(sessionId, "alice")).isEmpty();
        verify(pool).release(worker);
    }

    @Test
    void sessionWithRecentActivityIsNotReaped() {
        RecorderWorker worker = mock(RecorderWorker.class);
        when(pool.allocate()).thenReturn(Optional.of(worker));

        RecordingSession session = registry.open(1L, "https://example.com/start", "alice");
        UUID sessionId = session.sessionId();

        // Advance 10 minutes (within 15m timeout)
        clock.advance(Duration.ofMinutes(10));
        session.recordActivity();

        // Advance another 10 minutes (20m total from start, but only 10m since lastActivity)
        clock.advance(Duration.ofMinutes(10));

        registry.reapIdle();

        assertThat(registry.find(sessionId, "alice")).isPresent();
        verify(pool, never()).release(worker);
    }

    @Test
    void reapingIdleSessionReleasesWorkerEvenIfClosingContextThrows() {
        RecorderWorker worker = mock(RecorderWorker.class);
        when(pool.allocate()).thenReturn(Optional.of(worker));
        when(worker.submit(any())).thenReturn(null).thenThrow(new RuntimeException("Chromium context crash"));

        RecordingSession session = registry.open(1L, "https://example.com/start", "alice");
        UUID sessionId = session.sessionId();

        clock.advance(Duration.ofMinutes(16));

        registry.reapIdle();

        assertThat(registry.find(sessionId, "alice")).isEmpty();
        verify(pool).release(worker);
    }

    @Test
    void openWhenPoolIsAtCapacityThrowsCapacityExceptionNamingTheLimit() {
        when(pool.allocate()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registry.open(1L, "https://example.com/start", "alice"))
                .isInstanceOf(RecorderCapacityException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(RecorderCapacityException.class))
                .extracting(RecorderCapacityException::limit)
                .isEqualTo(2);
    }

    @Test
    void openWhenTheBrowserFailsToStartIsNotReportedAsBeingAtCapacity() {
        // A caller that cannot tell the two apart tells the user to wait for a colleague to
        // finish recording, which will never make a broken Chromium start.
        RecorderWorker worker = mock(RecorderWorker.class);
        when(pool.allocate()).thenReturn(Optional.of(worker));
        when(worker.submit(any())).thenThrow(new RuntimeException("Chromium connection failed"));

        assertThatThrownBy(() -> registry.open(1L, "https://example.com/start", "alice"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(RecorderCapacityException.class);

        verify(pool).release(worker);
    }

    @Test
    void idleReaperJobDrivesReapIdleOnRegistry() {
        RecordingSessionRegistry mockRegistry = mock(RecordingSessionRegistry.class);
        RecorderIdleReaperJob job = new RecorderIdleReaperJob(mockRegistry);

        job.reapIdle();

        verify(mockRegistry).reapIdle();
    }

    @Test
    void closeAllForUserClosesOnlyMatchingSessionsAndReleasesWorkers() {
        RecorderWorker worker1 = mock(RecorderWorker.class);
        RecorderWorker worker2 = mock(RecorderWorker.class);
        when(pool.allocate()).thenReturn(Optional.of(worker1), Optional.of(worker2));

        RecordingSession aliceSession = registry.open(1L, "https://example.com/a", "alice");
        RecordingSession bobSession = registry.open(1L, "https://example.com/b", "bob");

        assertThat(registry.activeSessionsForUser("alice")).isEqualTo(1);
        assertThat(registry.activeSessionsForUser("bob")).isEqualTo(1);

        int closed = registry.closeAllForUser("alice");

        assertThat(closed).isEqualTo(1);
        assertThat(registry.find(aliceSession.sessionId(), "alice")).isEmpty();
        assertThat(registry.find(bobSession.sessionId(), "bob")).isPresent();
        assertThat(registry.activeSessionsForUser("alice")).isEqualTo(0);
        assertThat(registry.activeSessionsForUser("bob")).isEqualTo(1);
        verify(pool).release(worker1);
        verify(pool, never()).release(worker2);
    }

    @Test
    void closeAllClosesEverySessionAndReleasesWorkers() {
        RecorderWorker worker1 = mock(RecorderWorker.class);
        RecorderWorker worker2 = mock(RecorderWorker.class);
        when(pool.allocate()).thenReturn(Optional.of(worker1), Optional.of(worker2));

        RecordingSession s1 = registry.open(1L, "https://example.com/a", "alice");
        RecordingSession s2 = registry.open(1L, "https://example.com/b", "bob");

        int closed = registry.closeAll();

        assertThat(closed).isEqualTo(2);
        assertThat(registry.activeSessions()).isEqualTo(0);
        verify(pool).release(worker1);
        verify(pool).release(worker2);
    }

    @Test
    void closeMethodHasPreDestroyAnnotation() throws NoSuchMethodException {
        assertThat(RecordingSessionRegistry.class.getMethod("close").isAnnotationPresent(jakarta.annotation.PreDestroy.class))
                .isTrue();
    }

    @Test
    void closeMethodCallsCloseAll() {
        RecorderWorker worker = mock(RecorderWorker.class);
        when(pool.allocate()).thenReturn(Optional.of(worker));
        registry.open(1L, "https://example.com/start", "alice");
        assertThat(registry.activeSessions()).isEqualTo(1);

        registry.close();

        assertThat(registry.activeSessions()).isEqualTo(0);
        verify(pool).release(worker);
    }


    private static class TestClock extends Clock {
        private Instant current;
        private final ZoneId zone = ZoneOffset.UTC;

        TestClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
