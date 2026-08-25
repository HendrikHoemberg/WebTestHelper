package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/**
 * Controller advice providing {@link TriageStatus} metadata to all views,
 * preventing hardcoded triage enum strings and static class SpEL access in templates (§13.1).
 */
@ControllerAdvice
public class TriageUiAdvice {

    @ModelAttribute("triageFormActions")
    public List<TriageStatus> formActions() {
        return TriageStatus.formActions();
    }

    @ModelAttribute("triageDefaultAction")
    public String defaultAction() {
        return TriageStatus.defaultFormAction().name();
    }

    @ModelAttribute("triageExpiryActions")
    public String expiryActions() {
        return TriageStatus.expiryActionNames();
    }

    @ModelAttribute("triageMandatoryReasonActions")
    public String mandatoryReasonActions() {
        return TriageStatus.mandatoryReasonActionNames();
    }

    @ModelAttribute("triageReasonActions")
    public String reasonActions() {
        return TriageStatus.reasonActionNames();
    }
}
