package dev.hendrikhoemberg.webtesthelper.model;

/** A message the browser logged to the console. */
public record ConsoleMessage(String level, String text, String location) {}