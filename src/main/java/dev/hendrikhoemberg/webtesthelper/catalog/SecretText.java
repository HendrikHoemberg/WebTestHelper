package dev.hendrikhoemberg.webtesthelper.catalog;

import java.util.Objects;

public final class SecretText {

    private final String resolved;
    private final String template;
    private final boolean sensitive;

    private SecretText(String resolved, String template, boolean sensitive) {
        this.resolved = resolved;
        this.template = template;
        this.sensitive = sensitive;
    }

    public static SecretText plain(String text) {
        return new SecretText(text, text, false);
    }

    public static SecretText of(String resolved, String template) {
        return new SecretText(resolved, template, true);
    }

    public String expose() {
        return resolved;
    }

    public boolean sensitive() {
        return sensitive;
    }

    @Override
    public String toString() {
        return template;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SecretText that)) return false;
        return Objects.equals(this.resolved, that.resolved);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(resolved);
    }
}
