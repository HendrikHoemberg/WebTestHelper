package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.JourneyEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.JourneyRepository;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteRepository;
import dev.hendrikhoemberg.webtesthelper.model.JourneyDefinition;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class JourneyService {

    private final JourneyRepository journeys;
    private final SiteRepository sites;

    public JourneyService(JourneyRepository journeys, SiteRepository sites) {
        this.journeys = journeys;
        this.sites = sites;
    }

    public long create(long siteId, String name, List<JourneyStep> steps) {
        if (!sites.existsById(siteId)) {
            throw new IllegalArgumentException("Site existiert nicht: " + siteId);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("journey.name.blank");
        }
        if (journeys.existsBySiteIdAndNameIgnoreCase(siteId, name)) {
            throw new IllegalArgumentException("journey.name.duplicate");
        }

        JourneyEntity entity = new JourneyEntity();
        entity.setSiteId(siteId);
        entity.setName(name);
        entity.setEnabled(true);
        entity.setSteps(renumberSteps(steps));
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        JourneyEntity saved = journeys.save(entity);
        return saved.getId();
    }

    public void update(long journeyId, String name, boolean enabled, List<JourneyStep> steps) {
        JourneyEntity entity = journeys.findById(journeyId)
                .orElseThrow(() -> new IllegalArgumentException("Journey existiert nicht: " + journeyId));

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("journey.name.blank");
        }
        if (journeys.existsBySiteIdAndNameIgnoreCaseAndIdNot(entity.getSiteId(), name, journeyId)) {
            throw new IllegalArgumentException("journey.name.duplicate");
        }

        entity.setName(name);
        entity.setEnabled(enabled);
        entity.setSteps(renumberSteps(steps));
        entity.setUpdatedAt(Instant.now());

        journeys.save(entity);
    }

    @Transactional(readOnly = true)
    public Optional<JourneyDefinition> findDefinition(long journeyId) {
        return journeys.findById(journeyId).map(this::toDefinition);
    }

    @Transactional(readOnly = true)
    public List<JourneyDefinition> findEnabledBySite(long siteId) {
        return journeys.findBySiteIdAndEnabledTrueOrderByNameAsc(siteId).stream()
                .map(this::toDefinition)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<JourneyDefinition> findBySite(long siteId) {
        return journeys.findBySiteIdOrderByNameAsc(siteId).stream()
                .map(this::toDefinition)
                .toList();
    }

    public void delete(long journeyId) {
        journeys.findById(journeyId).ifPresent(journeys::delete);
    }

    @Transactional(readOnly = true)
    public String resolveUniqueName(long siteId, String desiredName) {
        String base = (desiredName != null && !desiredName.isBlank()) ? desiredName.trim() : "Neuer Ablauf";
        if (!journeys.existsBySiteIdAndNameIgnoreCase(siteId, base)) {
            return base;
        }
        int counter = 2;
        while (journeys.existsBySiteIdAndNameIgnoreCase(siteId, base + " " + counter)) {
            counter++;
        }
        return base + " " + counter;
    }

    private List<JourneyStep> renumberSteps(List<JourneyStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<JourneyStep> renumbered = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            JourneyStep step = steps.get(i);
            if (step.ordinal() == i) {
                renumbered.add(step);
            } else {
                renumbered.add(new JourneyStep(
                        step.id(),
                        i,
                        step.action(),
                        step.locatorCandidates(),
                        step.value(),
                        step.assertion(),
                        step.optional(),
                        step.timeoutMs()
                ));
            }
        }
        return renumbered;
    }

    private JourneyDefinition toDefinition(JourneyEntity entity) {
        return new JourneyDefinition(
                entity.getId(),
                entity.getSiteId(),
                entity.getName(),
                entity.isEnabled(),
                entity.getSteps()
        );
    }
}
