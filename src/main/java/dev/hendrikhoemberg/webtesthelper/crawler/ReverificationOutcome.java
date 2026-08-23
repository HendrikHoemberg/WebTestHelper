package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;

import java.util.List;
import java.util.Set;

/**
 * The result of re-checking the dead-link findings at the end of a run (spec 8). A finding whose
 * subject comes back reachable on a later attempt was a transient failure and must not be reported;
 * only the survivors become findings.
 *
 * @param surviving         the findings that are still broken after re-verification
 * @param recoveredSubjects the subjects that healed (came back {@code OK}); every finding on one
 *                          of these is dropped — recovery is a property of the subject, not of the
 *                          individual finding
 * @param rechecked         how many distinct subjects were actually re-verified (never the count
 *                          of findings)
 */
public record ReverificationOutcome(List<CheckFinding> surviving, Set<String> recoveredSubjects,
                                    int rechecked) {
}
