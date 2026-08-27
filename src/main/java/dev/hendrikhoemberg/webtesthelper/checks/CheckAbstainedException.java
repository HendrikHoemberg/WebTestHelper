package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

/**
 * Thrown when an interaction check cannot judge a page (D86).
 *
 * <p>This is <em>not</em> {@link CheckEvaluationException}, whose javadoc says a throwing check
 * is a bug and should fail the run loudly. This says the page could not be judged, which is a fact
 * about the page and not about the code (for example, a restless page whose DOM never settles).
 *
 * <p>Why it cannot be an empty finding list: a check that returns {@code List.of()} is telling
 * coverage it looked and saw nothing, and §6.4 then lets the run resolve last week's findings on
 * that page. An abstention that returned empty would mark a page clean on the strength of never
 * having read it.
 *
 * <p>The runner catches this exception ahead of generic runtime exceptions, logs it at INFO,
 * and leaves the check type out of that target's {@code drivenTypes} while preserving it in
 * {@code candidateTypes}, without failing the run.
 */
public class CheckAbstainedException extends RuntimeException {

    private final CheckType type;
    private final String url;
    private final String reason;

    public CheckAbstainedException(CheckType type, String url, String reason) {
        super("Prüfung " + (type != null ? type.name() : "null") + " auf " + url + " enthielt sich: " + reason);
        this.type = type;
        this.url = url;
        this.reason = reason;
    }

    public CheckAbstainedException(CheckType type, String url, String reason, Throwable cause) {
        super("Prüfung " + (type != null ? type.name() : "null") + " auf " + url + " enthielt sich: " + reason, cause);
        this.type = type;
        this.url = url;
        this.reason = reason;
    }

    public CheckType type() {
        return type;
    }

    public String url() {
        return url;
    }

    public String reason() {
        return reason;
    }
}
