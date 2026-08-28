package dev.hendrikhoemberg.webtesthelper.model;

/**
 * Action to perform in a journey step (§10.3).
 */
public enum StepAction {
    GOTO,
    CLICK,
    FILL,
    SELECT,
    PRESS,
    HOVER,
    WAIT_FOR,
    ASSERT
}
