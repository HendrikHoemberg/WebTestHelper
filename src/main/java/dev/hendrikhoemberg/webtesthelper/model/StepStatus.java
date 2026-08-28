package dev.hendrikhoemberg.webtesthelper.model;

/**
 * The execution status of a single journey step (§10.3).
 */
public enum StepStatus {
    PASSED,
    DRIFTED,
    FAILED,
    SKIPPED
}
