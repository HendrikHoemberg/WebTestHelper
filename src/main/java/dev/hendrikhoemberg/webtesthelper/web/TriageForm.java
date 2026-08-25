package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/**
 * Form model for single and bulk triage actions (§13.4, spec 6.3).
 *
 * @param aktion   the target triage status
 * @param grund    the explanation / reason for the triage action
 * @param stummBis optional end date for MUTED triage
 * @param ids      finding IDs for bulk triage (empty for single finding triage)
 */
public record TriageForm(
        TriageStatus aktion,
        String grund,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate stummBis,
        List<Long> ids) {

    public TriageForm {
        ids = ids == null ? List.of() : List.copyOf(ids);
    }
}
