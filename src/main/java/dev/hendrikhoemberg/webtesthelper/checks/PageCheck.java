package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;

import java.util.List;

/**
 * A check that runs once per page over an immutable snapshot (spec 5.2, 7.3).
 *
 * <p>Implementations must be pure: same snapshot plus same config, same findings, same order.
 * No clock, no randomness, no I/O, no mutable state. A check that needs the network is a
 * different kind of check and belongs behind {@link dev.hendrikhoemberg.webtesthelper.model.RunFacts}.
 */
public interface PageCheck extends CheckDescriptor {

    List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config);
}