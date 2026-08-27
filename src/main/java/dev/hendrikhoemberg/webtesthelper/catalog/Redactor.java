package dev.hendrikhoemberg.webtesthelper.catalog;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class Redactor {

    public static final String MASK = "••••••";
    public static final Redactor NONE = new Redactor(Collections.emptyList());

    private final List<String> secrets;

    private Redactor(List<String> secrets) {
        this.secrets = secrets;
    }

    public static Redactor of(Collection<String> secrets) {
        if (secrets == null || secrets.isEmpty()) {
            return NONE;
        }
        List<String> filtered = secrets.stream()
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();

        if (filtered.isEmpty()) {
            return NONE;
        }
        return new Redactor(filtered);
    }

    public String redact(String text) {
        if (text == null) {
            return null;
        }
        if (isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : secrets) {
            result = result.replace(secret, MASK);
        }
        return result;
    }

    public boolean isEmpty() {
        return secrets.isEmpty();
    }
}
