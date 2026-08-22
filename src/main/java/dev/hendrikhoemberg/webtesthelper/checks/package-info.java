/**
 * The check catalog (spec 7). A page check is a pure function from a
 * {@link dev.hendrikhoemberg.webtesthelper.model.PageSnapshot} to a list of
 * {@link dev.hendrikhoemberg.webtesthelper.model.CheckFinding}: no Spring beans, no database,
 * no browser (spec 5.1). That is what lets the catalog be developed against hand-built
 * snapshots and regression-tested against the fixture site.
 *
 * <p>Deliberately flat, like {@code model}: Spring Modulith treats a module's sub-packages as
 * internal, and the registry has to see every implementation.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Checks",
        allowedDependencies = {"model"})
package dev.hendrikhoemberg.webtesthelper.checks;