package dev.hendrikhoemberg.webtesthelper.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("webtesthelper.admin")
public record AdminProperties(String username, String password) {
}
