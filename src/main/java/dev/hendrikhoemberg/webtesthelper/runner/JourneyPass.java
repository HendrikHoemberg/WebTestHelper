package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.JourneyService;
import dev.hendrikhoemberg.webtesthelper.findings.JourneyFindingMapper;
import dev.hendrikhoemberg.webtesthelper.findings.MaterialisedFinding;
import dev.hendrikhoemberg.webtesthelper.model.JourneyDefinition;
import dev.hendrikhoemberg.webtesthelper.model.JourneyReplayResult;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Replays a site's configured journeys inside a run (§10.4, D107).
 *
 * <p>Lifecycle & scope rules:
 * <ul>
 *   <li>A {@link RunScope#PULSE} run replays no journeys (returns empty findings and empty completed journeys).</li>
 *   <li>{@link RunScope#FULL} and {@link RunScope#DEEP} replay only enabled journeys for the site.</li>
 *   <li>Per-journey error containment (D79, D86): if a replay throws an exception, it is caught and logged.
 *       The failed journey is omitted from {@code completedJourneyIds} so its prior findings are not resolved,
 *       and the pass continues with other journeys.</li>
 *   <li>Completed replays have their findings mapped via {@link JourneyFindingMapper#map} and aggregated.</li>
 * </ul>
 */
@Component
public class JourneyPass {

    private static final Logger log = LoggerFactory.getLogger(JourneyPass.class);

    private final JourneyService journeyService;
    private final JourneyReplayer replayer;

    public JourneyPass(JourneyService journeyService, JourneyReplayer replayer) {
        this.journeyService = Objects.requireNonNull(journeyService, "journeyService");
        this.replayer = Objects.requireNonNull(replayer, "replayer");
    }

    public JourneyPassResult run(SiteContext site, RunScope scope, Path artifacts) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(scope, "scope");

        if (scope == RunScope.PULSE) {
            return JourneyPassResult.NONE;
        }

        List<JourneyDefinition> journeys = journeyService.findBySite(site.siteId());
        if (journeys.isEmpty()) {
            return JourneyPassResult.NONE;
        }

        List<MaterialisedFinding> allFindings = new ArrayList<>();
        Set<Long> completedJourneyIds = new LinkedHashSet<>();

        for (JourneyDefinition journey : journeys) {
            if (!journey.enabled()) {
                continue;
            }

            try {
                JourneyReplayResult result = replayer.replay(journey, site, artifacts);
                if (result != null) {
                    if (journey.id() != null) {
                        completedJourneyIds.add(journey.id());
                    }
                    List<MaterialisedFinding> mapped = JourneyFindingMapper.map(journey, result);
                    allFindings.addAll(mapped);
                }
            } catch (RuntimeException e) {
                log.warn("Lauf: Replay von Journey {} ('{}') auf Website {} fehlgeschlagen: {}",
                        journey.id(), journey.name(), site.siteId(), e.getMessage(), e);
            }
        }

        return new JourneyPassResult(allFindings, completedJourneyIds);
    }
}
