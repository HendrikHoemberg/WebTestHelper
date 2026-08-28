package dev.hendrikhoemberg.webtesthelper.recorder;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller managing the interactive recording screen and session lifecycle (§10.1, §12, §13.4).
 *
 * <p>Allocates a new {@link RecordingSession} from {@link RecordingSessionRegistry}, rendering the live
 * screencast canvas view. When pool capacity is reached (2 concurrent sessions max), renders the
 * capacity-exceeded state in German without allocating.
 *
 * <p>Provides endpoints to leave/close an active session cleanly when the user finishes or navigates away.
 */
@Controller
public class RecorderController {

    private final SiteService siteService;
    private final RecordingSessionRegistry sessionRegistry;

    public RecorderController(SiteService siteService, RecordingSessionRegistry sessionRegistry) {
        this.siteService = Objects.requireNonNull(siteService, "siteService must not be null");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry must not be null");
    }

    @GetMapping({"/websites/{siteId}/aufzeichnen", "/sites/{siteId}/journeys/aufzeichnen", "/sites/{siteId}/record", "/websites/{siteId}/record"})
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
            return "journey/record";
        } catch (IllegalStateException e) {
            model.addAttribute("site", site);
            model.addAttribute("capacityExceeded", true);
            model.addAttribute("capacityLimit", 2);
            model.addAttribute("errorMessage", "Maximale Anzahl gleichzeitiger Aufzeichnungssitzungen (2) erreicht. Bitte beenden Sie eine laufende Sitzung oder versuchen Sie es später erneut.");
            return "journey/record";
        }
    }

    @PostMapping({"/recorder/{sessionId}/beenden", "/recorder/{sessionId}/schliessen", "/recorder/{sessionId}/close"})
    public String closeSession(@PathVariable("sessionId") UUID sessionId,
                               @RequestParam(value = "siteId", required = false) Long siteId,
                               Principal principal) {
        String username = principal != null ? principal.getName() : null;
        long targetSiteId = siteId != null ? siteId : 0L;
        if (targetSiteId == 0L && username != null) {
            targetSiteId = sessionRegistry.find(sessionId, username)
                    .map(RecordingSession::siteId)
                    .orElse(0L);
        }
        sessionRegistry.close(sessionId);
        if (targetSiteId > 0) {
            return "redirect:/sites/" + targetSiteId + "/journeys";
        }
        return "redirect:/websites";
    }

    @DeleteMapping("/recorder/{sessionId}")
    @ResponseBody
    public ResponseEntity<Void> deleteSession(@PathVariable("sessionId") UUID sessionId, Principal principal) {
        sessionRegistry.close(sessionId);
        return ResponseEntity.noContent().build();
    }
}
