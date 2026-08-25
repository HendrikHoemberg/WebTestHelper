package dev.hendrikhoemberg.webtesthelper.crawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void deletesAnExistingRunDirectoryRecursivelyAndReturnsOne() throws Exception {
        Path runDir = tempDir.resolve("42");
        Files.createDirectories(runDir.resolve("nested"));
        Files.write(runDir.resolve("nested/abc.png"), new byte[]{1, 2, 3});
        Files.write(runDir.resolve("top.png"), new byte[]{4, 5, 6});

        ArtifactStore store = new ArtifactStore(tempDir);

        assertThat(store.deleteRunArtifacts(List.of(42L))).isEqualTo(1);
        assertThat(runDir).doesNotExist();
    }

    @Test
    void aRunIdWithoutADirectoryIsNotAnErrorAndDoesNotCount() {
        ArtifactStore store = new ArtifactStore(tempDir);

        assertThat(store.deleteRunArtifacts(List.of(1L, 2L, 3L))).isZero();
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void directoriesNotNamedInTheCallAreUntouched() throws Exception {
        Files.createDirectories(tempDir.resolve("7"));
        Files.createDirectories(tempDir.resolve("42"));
        // A directory whose name is not a number at all must be ignored, not interpreted.
        Files.createDirectories(tempDir.resolve("abc"));

        ArtifactStore store = new ArtifactStore(tempDir);

        assertThat(store.deleteRunArtifacts(List.of(7L))).isEqualTo(1);
        assertThat(tempDir.resolve("7")).doesNotExist();
        assertThat(tempDir.resolve("42")).exists();
        assertThat(tempDir.resolve("abc")).exists();
    }

    @Test
    void neverResolvesOutsideTheArtifactDirectory() throws Exception {
        // The store only ever touches directories resolved under its base; a sibling outside
        // it is structurally unreachable whatever ids are handed in.
        Path base = tempDir.resolve("artifacts");
        Files.createDirectories(base.resolve("7"));
        Files.createDirectories(tempDir.resolve("decoy"));

        ArtifactStore store = new ArtifactStore(base);

        assertThat(store.deleteRunArtifacts(List.of(7L))).isEqualTo(1);
        assertThat(base.resolve("7")).doesNotExist();
        assertThat(tempDir.resolve("decoy")).exists();
    }
}
