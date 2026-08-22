package dev.hendrikhoemberg.webtesthelper.model;

public enum UrlStatus {

    OK,
    DEAD,
    UNVERIFIABLE;

    public static UrlStatus ofHttpStatus(int status) {
        if (status >= 200 && status < 400) {
            return OK;
        }
        return switch (status) {
            case 401, 403, 407, 429, 451, 999 -> UNVERIFIABLE;
            default -> DEAD;
        };
    }
}