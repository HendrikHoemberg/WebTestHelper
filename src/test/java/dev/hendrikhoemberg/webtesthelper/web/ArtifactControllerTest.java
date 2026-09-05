package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.auth.AppUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(ArtifactController.class)
class ArtifactControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("webtesthelper.crawler.artifact-dir", () -> tempDir.toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AppUserService appUserService;

    @Test
    @WithMockUser(roles = "USER")
    void streamsRealScreenshotWithImagePngContentType() throws Exception {
        long runId = 10L;
        String filename = "0123456789abcdef0123456789abcdef.png";
        Path runDir = tempDir.resolve(String.valueOf(runId));
        Files.createDirectories(runDir);
        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        Files.write(runDir.resolve(filename), pngBytes);

        mvc.perform(get("/artefakte/" + runId + "/" + filename))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE))
                .andExpect(content().bytes(pngBytes));
    }

    @Test
    @WithMockUser(roles = "USER")
    void invalidFilenamesAndTraversalReturn404() throws Exception {
        // Path traversal with encoded slash
        mvc.perform(get("/artefakte/{runId}/{name}", 10L, "..%2Fetc%2Fpasswd"))
                .andExpect(status().isNotFound());

        // Non-hex name
        mvc.perform(get("/artefakte/{runId}/{name}", 10L, "not-a-valid-screenshot.png"))
                .andExpect(status().isNotFound());

        // Short hex name
        mvc.perform(get("/artefakte/{runId}/{name}", 10L, "abcd1234.png"))
                .andExpect(status().isNotFound());

        // Wrong extension
        mvc.perform(get("/artefakte/{runId}/{name}", 10L, "0123456789abcdef0123456789abcdef.jpg"))
                .andExpect(status().isNotFound());

        // Valid name format but non-existent file
        mvc.perform(get("/artefakte/{runId}/{name}", 10L, "0123456789abcdef0123456789abcdef.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousRequestIsRedirectedToLogin() throws Exception {
        String filename = "0123456789abcdef0123456789abcdef.png";

        mvc.perform(get("/artefakte/10/" + filename))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));
    }
}
