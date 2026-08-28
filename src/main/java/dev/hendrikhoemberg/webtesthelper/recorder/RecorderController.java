package dev.hendrikhoemberg.webtesthelper.recorder;

import dev.hendrikhoemberg.webtesthelper.catalog.JourneyService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller managing the interactive recording screen and session lifecycle (§10.1, §12, §13.4).
 *
 * <p>Allocates a new {@link RecordingSession} from {@link RecordingSessionRegistry}, rendering the live
 * screencast canvas view. When every recorder worker is allocated, renders the capacity-exceeded
 * state in German naming the configured limit (§13.4) — a browser that fails to start is a
 * different failure and is not dressed up as one the user can wait out.
 *
 * <p>Closing goes through {@link RecordingSessionRegistry#find(UUID, String)}, so ownership is
 * enforced in the one place Task 3 put it rather than trusting whoever holds the session id.
 */
@Controller
public class RecorderController {

    private static final Logger log = LoggerFactory.getLogger(RecorderController.class);

    private final SiteService siteService;
    private final RecordingSessionRegistry sessionRegistry;
    private final JourneyService journeyService;

    public RecorderController(SiteService siteService, RecordingSessionRegistry sessionRegistry, JourneyService journeyService) {
        this.siteService = Objects.requireNonNull(siteService, "siteService must not be null");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry must not be null");
        this.journeyService = Objects.requireNonNull(journeyService, "journeyService must not be null");
    }

    @GetMapping("/websites/{siteId}/aufzeichnen")
    public String record(@PathVariable("siteId") long siteId,
                         @RequestParam(value = "startUrl", required = false) String startUrl,
                         Principal principal,
                         Model model) {
        SiteContext site;
        try {
            site = siteService.contextFor(siteId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Website nicht gefunden: " + siteId, e);
        }

        String username = principal != null ? principal.getName() : "anonymous";
        String effectiveStartUrl = (startUrl != null && !startUrl.isBlank()) ? startUrl : site.baseUrl().value();

        try {
            RecordingSession session = sessionRegistry.open(siteId, effectiveStartUrl, username);
            model.addAttribute("site", site);
            model.addAttribute("session", session);
            model.addAttribute("sessionId", session.sessionId());
            model.addAttribute("wsUrl", "/recorder/ws/" + session.sessionId());
            model.addAttribute("capacityExceeded", false);
            model.addAttribute("startFailed", false);
            return "journey/record";
        } catch (RecorderCapacityException e) {
            model.addAttribute("site", site);
            model.addAttribute("capacityExceeded", true);
            model.addAttribute("startFailed", false);
            model.addAttribute("capacityLimit", e.limit());
            return "journey/record";
        } catch (RuntimeException e) {
            log.warn("Aufnahmesitzung für Website {} konnte nicht gestartet werden", siteId, e);
            model.addAttribute("site", site);
            model.addAttribute("capacityExceeded", false);
            model.addAttribute("startFailed", true);
            return "journey/record";
        }
    }

    @PostMapping("/recorder/{sessionId}/beenden")
    public String closeSession(@PathVariable("sessionId") UUID sessionId,
                               Principal principal) {
        String username = principal != null ? principal.getName() : null;
        RecordingSession session = sessionRegistry.find(sessionId, username)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Aufnahmesitzung nicht gefunden: " + sessionId));

        long siteId = session.siteId();
        sessionRegistry.close(sessionId);
        return "redirect:/sites/" + siteId + "/journeys";
    }

    @PostMapping("/recorder/{sessionId}/speichern")
    public String saveSession(@PathVariable("sessionId") UUID sessionId,
                              @RequestParam(value = "name", required = false) String name,
                              Principal principal) {
        String username = principal != null ? principal.getName() : null;
        RecordingSession session = sessionRegistry.find(sessionId, username)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Aufnahmesitzung nicht gefunden: " + sessionId));

        long siteId = session.siteId();
        String startUrl = session.startUrl();
        IntentCapture capture = session.intentCapture();
        List<CapturedEvent> events = capture != null ? capture.drain() : List.of();
        List<JourneyStep> steps = StepBuilder.build(events, startUrl);

        String effectiveName = (name != null && !name.isBlank()) ? name.trim() : "Neuer Ablauf";
        long journeyId = journeyService.create(siteId, effectiveName, steps);
        sessionRegistry.close(sessionId);
        return "redirect:/sites/" + siteId + "/journeys/" + journeyId + "/bearbeiten";
    }
}
