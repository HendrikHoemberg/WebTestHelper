package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

/**
 * A check threw. Spec 14's "one bad page must never kill a run" is about pages: a check that
 * throws is deterministic, would fail every run of every site until someone fixed it, and
 * silently dropping its findings would mean a site quietly stops being checked. So it fails
 * the run — loudly, and saying which check on which page.
 */
public class CheckEvaluationException extends RuntimeException {

    public CheckEvaluationException(CheckType type, String url, Throwable cause) {
        super("Prüfung " + type.name() + " fehlgeschlagen für " + url, cause);
    }
}