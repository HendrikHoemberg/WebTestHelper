package dev.hendrikhoemberg.webtesthelper.catalog;

import java.util.Optional;

public enum CredentialField {
    USERNAME("username"),
    PASSWORD("password");

    private final String token;

    CredentialField(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static Optional<CredentialField> parse(String token) {
        if ("username".equals(token)) {
            return Optional.of(USERNAME);
        }
        if ("password".equals(token)) {
            return Optional.of(PASSWORD);
        }
        return Optional.empty();
    }
}
