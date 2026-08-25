package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.crawler.ArtifactStore;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunResultJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prunes crawl artifacts for runs beyond the most recent {@code artifactRetentionRuns} per site.
 * The module that knows which runs to prune ({@code runner}) calls the module that knows where
 * the files live ({@code crawler}); the split follows the module direction (runner → crawler,
 * never the reverse) and needs no property naming the other module's directory.
 */
@Component
public class ArtifactRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactRetentionService.class);

    private final RunResultJdbcRepository runs;
    private final ArtifactStore store;
    private final RunnerProperties properties;

    public ArtifactRetentionService(RunResultJdbcRepository runs, ArtifactStore store,
            RunnerProperties properties) {
        this.runs = runs;
        this.store = store;
        this.properties = properties;
    }

    /**
     * Removes artifact directories for expired runs. Destructive, so its one trace is an INFO
     * line with both counts rather than an absence of files.
     */
    public int prune() {
        List<Long> expired = runs.findExpiredArtifactRunIds(properties.artifactRetentionRuns());
        int deleted = store.deleteRunArtifacts(expired);
        log.info("Artefakt-Aufbewahrung: {} Run-Verzeichnisse entfernt ({} verworfen)",
                deleted, expired.size());
        return deleted;
    }
}
