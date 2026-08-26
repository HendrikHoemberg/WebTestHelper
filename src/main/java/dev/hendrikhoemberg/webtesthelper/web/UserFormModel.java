package dev.hendrikhoemberg.webtesthelper.web;

/**
 * The create-user form on the Settings screen. A new account always starts as {@code USER} — the
 * least-privilege default — and is promoted via a row action if it should become an administrator.
 */
public class UserFormModel {

    private String username;
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
