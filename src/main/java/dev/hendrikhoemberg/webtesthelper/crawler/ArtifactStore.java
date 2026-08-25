package dev.hendrikhoemberg.webtesthelper.crawler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Deletes crawl artifact directories on disk. This is the one place in the system that removes
 * files, so the safety invariants are a test rather than a comment: a run directory is removed
 * only when an explicit run id is handed in, never outside the artifact base, and a name that is
 * not a run id is ignored rather than interpreted.
 */
@Component
public class ArtifactStore {

    private final Path artifactDir;

    public ArtifactStore(
            @Value("${webtesthelper.crawler.artifact-dir:${webtesthelper.data-dir:./data}/artifacts}") Path artifactDir) {
        this.artifactDir = artifactDir;
    }

    /**
     * Removes the run artifact directories for the given run ids. Returns how many directories
     * were actually removed. A run id without a directory is not an error and does not count.
     */
    public int deleteRunArtifacts(Collection<Long> runIds) {
        Path base = artifactDir.toAbsolutePath().normalize();
        int deleted = 0;
        for (Long runId : runIds) {
            if (runId == null) {
                continue;
            }
            Path runDir = base.resolve(String.valueOf(runId)).normalize();
            if (!runDir.startsWith(base) || !Files.isDirectory(runDir)) {
                continue;
            }
            deleteRecursively(runDir);
            deleted++;
        }
        return deleted;
    }

    private void deleteRecursively(Path dir) {
        try {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Run-Artefakt nicht löschbar: " + path, e);
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Run-Artefakt-Verzeichnis nicht lesbar: " + dir, e);
        }
    }
}
