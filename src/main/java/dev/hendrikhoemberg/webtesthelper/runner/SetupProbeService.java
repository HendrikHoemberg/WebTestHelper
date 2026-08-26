package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.crawler.ProbeEvidence;
import dev.hendrikhoemberg.webtesthelper.crawler.SetupProbe;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The background half of guided setup (D67): runs {@link SetupProbe} off the request thread and
 * keeps the resulting {@link ProbeState} in memory, per process. Probes are rare and each one
 * competes for the same browser workers a crawl uses, so a single daemon thread is enough — a
 * second probe thread would buy contention rather than throughput.
 */
@Service
public class SetupProbeService {

    /** D67: nobody returns to a proposal an hour later; stale entries are swept on every start. */
    private static final Duration RESULT_TTL = Duration.ofHours(1);

    private final SetupProbe probe;
    private final SiteService sites;
    private final Map<Long, ProbeState> states = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "setup-probe");
        thread.setDaemon(true);
        return thread;
    });

    public SetupProbeService(SetupProbe probe, SiteService sites) {
        this.probe = probe;
        this.sites = sites;
    }

    /** Test seam only, as on {@link dev.hendrikhoemberg.webtesthelper.runner.RunWorker}. */
    void putStateForTest(long siteId, ProbeState state) {
        states.put(siteId, state);
    }

    /**
     * Kicks off a probe for a site, or does nothing if one is already running. Stale entries —
     * older than the result TTL — are swept on every call.
     */
    public void start(long siteId) {
        sweep();
        ProbeState current = states.get(siteId);
        if (current != null && current.status() == ProbeStatus.LAEUFT) {
            return;
        }
        states.put(siteId, new ProbeState(ProbeStatus.LAEUFT, Instant.now(), null, null));
        executor.submit(() -> complete(siteId));
    }

    public Optional<ProbeState> stateOf(long siteId) {
        return Optional.ofNullable(states.get(siteId));
    }

    public void clear(long siteId) {
        states.remove(siteId);
    }

    private void complete(long siteId) {
        try {
            ProbeEvidence evidence = probe.probe(sites.contextFor(siteId));
            states.put(siteId, new ProbeState(ProbeStatus.FERTIG, Instant.now(),
                    new SetupProposal(evidence, SetupProposals.of(evidence)), null));
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            states.put(siteId, new ProbeState(ProbeStatus.FEHLGESCHLAGEN, Instant.now(), null, message));
        }
    }

    private void sweep() {
        Instant cutoff = Instant.now().minus(RESULT_TTL);
        states.entrySet().removeIf(entry -> entry.getValue().startedAt().isBefore(cutoff));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
