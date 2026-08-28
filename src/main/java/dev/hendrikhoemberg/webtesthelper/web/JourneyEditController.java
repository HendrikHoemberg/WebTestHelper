package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.JourneyService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.AssertionType;
import dev.hendrikhoemberg.webtesthelper.model.JourneyDefinition;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.StepAssertion;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Controller for editing journey definitions and their steps (§10.4).
 *
 * <p>Supports:
 * <ul>
 *   <li>Renaming and toggling enabled state</li>
 *   <li>Deleting steps while preserving all other step UUIDs and renumbering densely</li>
 *   <li>Reordering steps while preserving step UUIDs</li>
 *   <li>Editing step values (verbatim credential templates round-trip safely)</li>
 *   <li>Marking steps optional or required</li>
 *   <li>Adding, modifying, or removing step assertions (all four {@link AssertionType}s)</li>
 * </ul>
 */
@Controller
public class JourneyEditController {

    private final JourneyService journeyService;
    private final SiteService siteService;

    public JourneyEditController(JourneyService journeyService, SiteService siteService) {
        this.journeyService = Objects.requireNonNull(journeyService, "journeyService must not be null");
        this.siteService = Objects.requireNonNull(siteService, "siteService must not be null");
    }

    @GetMapping({"/sites/{siteId}/journeys/{journeyId}/bearbeiten", "/websites/{siteId}/reisen/{journeyId}/bearbeiten"})
    public String editForm(@PathVariable("siteId") long siteId,
                           @PathVariable("journeyId") long journeyId,
                           Model model) {
        SiteContext site = siteService.contextFor(siteId);
        JourneyDefinition journey = journeyService.findDefinition(journeyId)
                .filter(j -> Objects.equals(j.siteId(), siteId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ablauf nicht gefunden: " + journeyId));

        model.addAttribute("site", site);
        model.addAttribute("journey", journey);
        model.addAttribute("form", JourneyEditForm.from(journey));
        model.addAttribute("assertionTypes", AssertionType.values());
        return "journey/edit";
    }

    @PostMapping({"/sites/{siteId}/journeys/{journeyId}/bearbeiten", "/websites/{siteId}/reisen/{journeyId}/bearbeiten"})
    public String updateJourney(@PathVariable("siteId") long siteId,
                                @PathVariable("journeyId") long journeyId,
                                @ModelAttribute("form") JourneyEditForm form,
                                Model model) {
        SiteContext site = siteService.contextFor(siteId);
        JourneyDefinition existingJourney = journeyService.findDefinition(journeyId)
                .filter(j -> Objects.equals(j.siteId(), siteId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ablauf nicht gefunden: " + journeyId));

        Map<UUID, JourneyStep> existingStepMap = existingJourney.steps().stream()
                .collect(Collectors.toMap(JourneyStep::id, Function.identity(), (a, b) -> a));

        List<StepEditItem> submittedSteps = form.getSteps() != null ? form.getSteps() : List.of();

        List<StepEditItem> activeItems = submittedSteps.stream()
                .filter(s -> s != null && !s.isDeleted() && s.getId() != null)
                .sorted(Comparator.comparingInt(StepEditItem::getOrdinal))
                .toList();

        List<JourneyStep> updatedSteps = new ArrayList<>();
        for (int i = 0; i < activeItems.size(); i++) {
            StepEditItem item = activeItems.get(i);
            JourneyStep existing = existingStepMap.get(item.getId());

            StepAction action = item.getAction() != null ? item.getAction()
                    : (existing != null ? existing.action() : StepAction.GOTO);
            List<LocatorCandidate> candidates = (existing != null && existing.locatorCandidates() != null)
                    ? existing.locatorCandidates()
                    : (item.getLocatorCandidates() != null ? item.getLocatorCandidates() : List.of());
            int timeout = item.getTimeoutMs() > 0 ? item.getTimeoutMs()
                    : (existing != null ? existing.timeoutMs() : JourneyStep.DEFAULT_TIMEOUT_MS);

            StepAssertion assertion = null;
            if (item.getAssertionType() != null) {
                String exp = (item.getAssertionExpected() != null && !item.getAssertionExpected().isBlank())
                        ? item.getAssertionExpected()
                        : null;
                assertion = new StepAssertion(item.getAssertionType(), exp);
            }

            JourneyStep updatedStep = new JourneyStep(
                    item.getId(),
                    i,
                    action,
                    candidates,
                    item.getValue(),
                    assertion,
                    item.isOptional(),
                    timeout
            );
            updatedSteps.add(updatedStep);
        }

        try {
            journeyService.update(journeyId, form.getName(), form.isEnabled(), updatedSteps);
            return "redirect:/sites/" + siteId + "/journeys/" + journeyId;
        } catch (IllegalArgumentException e) {
            model.addAttribute("site", site);
            model.addAttribute("journey", existingJourney);
            model.addAttribute("form", form);
            model.addAttribute("assertionTypes", AssertionType.values());
            model.addAttribute("errorMessage", e.getMessage());
            return "journey/edit";
        }
    }

    public static class JourneyEditForm {
        private String name;
        private boolean enabled = true;
        private List<StepEditItem> steps = new ArrayList<>();

        public JourneyEditForm() {
        }

        public JourneyEditForm(String name, boolean enabled, List<StepEditItem> steps) {
            this.name = name;
            this.enabled = enabled;
            this.steps = steps != null ? steps : new ArrayList<>();
        }

        public static JourneyEditForm from(JourneyDefinition journey) {
            List<StepEditItem> items = journey.steps().stream()
                    .map(StepEditItem::from)
                    .collect(Collectors.toCollection(ArrayList::new));
            return new JourneyEditForm(journey.name(), journey.enabled(), items);
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<StepEditItem> getSteps() {
            return steps;
        }

        public void setSteps(List<StepEditItem> steps) {
            this.steps = steps != null ? steps : new ArrayList<>();
        }
    }

    public static class StepEditItem {
        private UUID id;
        private int ordinal;
        private StepAction action;
        private List<LocatorCandidate> locatorCandidates = new ArrayList<>();
        private String value;
        private boolean optional;
        private int timeoutMs = JourneyStep.DEFAULT_TIMEOUT_MS;
        private AssertionType assertionType;
        private String assertionExpected;
        private boolean deleted;

        public StepEditItem() {
        }

        public static StepEditItem from(JourneyStep step) {
            StepEditItem item = new StepEditItem();
            item.setId(step.id());
            item.setOrdinal(step.ordinal());
            item.setAction(step.action());
            item.setLocatorCandidates(step.locatorCandidates());
            item.setValue(step.value());
            item.setOptional(step.optional());
            item.setTimeoutMs(step.timeoutMs());
            if (step.assertion() != null) {
                item.setAssertionType(step.assertion().type());
                item.setAssertionExpected(step.assertion().expected());
            }
            return item;
        }

        public UUID getId() {
            return id;
        }

        public void setId(UUID id) {
            this.id = id;
        }

        public int getOrdinal() {
            return ordinal;
        }

        public void setOrdinal(int ordinal) {
            this.ordinal = ordinal;
        }

        public StepAction getAction() {
            return action;
        }

        public void setAction(StepAction action) {
            this.action = action;
        }

        public List<LocatorCandidate> getLocatorCandidates() {
            return locatorCandidates;
        }

        public void setLocatorCandidates(List<LocatorCandidate> locatorCandidates) {
            this.locatorCandidates = locatorCandidates != null ? locatorCandidates : new ArrayList<>();
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public boolean isOptional() {
            return optional;
        }

        public void setOptional(boolean optional) {
            this.optional = optional;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public AssertionType getAssertionType() {
            return assertionType;
        }

        public void setAssertionType(AssertionType assertionType) {
            this.assertionType = assertionType;
        }

        public String getAssertionExpected() {
            return assertionExpected;
        }

        public void setAssertionExpected(String assertionExpected) {
            this.assertionExpected = assertionExpected;
        }

        public boolean isDeleted() {
            return deleted;
        }

        public void setDeleted(boolean deleted) {
            this.deleted = deleted;
        }
    }
}
