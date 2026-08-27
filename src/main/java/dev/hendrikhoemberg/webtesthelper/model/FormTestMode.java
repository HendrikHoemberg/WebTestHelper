package dev.hendrikhoemberg.webtesthelper.model;

/** Contact-form test modes (spec 7.2). Stored in Phase 1; acted on in Phase 3. */
public enum FormTestMode {
    NO_SUBMIT, SUBMIT, SUBMIT_AND_VERIFY_MAIL;

    public boolean submits() {
        return this != NO_SUBMIT;
    }

    /**
     * The mode effective for the given run scope.
     *
     * <p>Returns {@code this} for {@link RunScope#DEEP}, {@link #NO_SUBMIT} when {@code this == NO_SUBMIT},
     * and {@code null} otherwise — meaning "this run must not judge this form at all" (D90).
     */
    public FormTestMode effectiveFor(RunScope scope) {
        if (scope == RunScope.DEEP) {
            return this;
        }
        if (this == NO_SUBMIT) {
            return NO_SUBMIT;
        }
        return null;
    }
}
