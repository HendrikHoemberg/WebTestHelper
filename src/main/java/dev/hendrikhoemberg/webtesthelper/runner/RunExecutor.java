package dev.hendrikhoemberg.webtesthelper.runner;

/** Executes one leased run. Plan 2 replaces the no-op with the crawler pipeline. */
public interface RunExecutor {

    void execute(RunLease lease) throws Exception;
}