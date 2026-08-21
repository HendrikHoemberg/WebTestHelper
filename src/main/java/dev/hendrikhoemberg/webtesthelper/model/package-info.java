/**
 * Shared value types. This module depends on nothing — it is what lets {@code checks} and
 * {@code findings} depend only on value types (spec 5.1, plan deviation D1).
 *
 * <p>Deliberately flat: Spring Modulith treats a module's sub-packages as internal, so a
 * type in {@code model.url} would be invisible to {@code crawler}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Value types",
        allowedDependencies = {})
package dev.hendrikhoemberg.webtesthelper.model;
