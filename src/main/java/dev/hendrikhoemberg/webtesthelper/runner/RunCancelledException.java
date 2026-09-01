package dev.hendrikhoemberg.webtesthelper.runner;

/**
 * Raised by the executor when the run's lease is no longer RUNNING — a user cancelled it, or
 * (rarely) the sweep requeued it. The worker must not finish such a run as COMPLETED or
 * FAILED: the run row already carries the truth.
 */
public final class RunCancelledException extends RuntimeException {

    public RunCancelledException(String message) {
        super(message);
    }
}
