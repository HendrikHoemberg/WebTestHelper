package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;

/** A form as found in the page. */
public record FormRef(String id, String action, String method, List<FormFieldRef> fields) {

    public FormRef {
        fields = List.copyOf(fields);
    }
}