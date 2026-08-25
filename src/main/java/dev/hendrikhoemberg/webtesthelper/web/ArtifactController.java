package dev.hendrikhoemberg.webtesthelper.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Streams screenshot artifacts saved by crawler workers.
 * Screenshots are named with a 32-hex-character SHA-256 hash plus {@code .png} (spec 16).
 * Access requires authentication (SecurityConfig).
 */
@Controller
public class ArtifactController {

    private static final Pattern SCREENSHOT_PATTERN = Pattern.compile("^[0-9a-f]{32}\\.png$");

    private final Path artifactDir;

    public ArtifactController(
            @Value("${webtesthelper.crawler.artifact-dir:${webtesthelper.data-dir:./data}/artifacts}") Path artifactDir) {
        this.artifactDir = artifactDir;
    }

    @GetMapping("/artefakte/{runId}/{screenshotPath}")
    public ResponseEntity<Resource> screenshot(
            @PathVariable("runId") long runId,
            @PathVariable("screenshotPath") String screenshotPath) {

        if (!SCREENSHOT_PATTERN.matcher(screenshotPath).matches()) {
            return ResponseEntity.notFound().build();
        }

        Path artifactBase = artifactDir.toAbsolutePath().normalize();
        Path file = artifactBase.resolve(String.valueOf(runId)).resolve(screenshotPath).normalize();

        if (!file.startsWith(artifactBase) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(resource);
    }
}
