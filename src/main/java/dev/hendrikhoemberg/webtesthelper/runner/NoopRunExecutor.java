package dev.hendrikhoemberg.webtesthelper.runner;

import org.springframework.stereotype.Component;

/** Plan 1 placeholder: the run succeeds without doing anything. */
@Component
public class NoopRunExecutor implements RunExecutor {

    @Override
    public void execute(RunLease lease) {
        // Plan 2: crawl + checks + materialise + diff
    }
}