package dev.hendrikhoemberg.webtesthelper.model;

/** A named input inside a form. */
public record FormFieldRef(String name, String type, String label,
                           String autocomplete, boolean required) {}