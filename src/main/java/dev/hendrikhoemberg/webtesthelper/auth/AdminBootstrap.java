package dev.hendrikhoemberg.webtesthelper.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserService appUserService;
    private final AdminProperties adminProperties;

    public AdminBootstrap(AppUserService appUserService, AdminProperties adminProperties) {
        this.appUserService = appUserService;
        this.adminProperties = adminProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!appUserService.isEmpty()) {
            return;
        }

        String username = (adminProperties != null && adminProperties.username() != null && !adminProperties.username().isBlank())
                ? adminProperties.username().trim()
                : "admin";

        String password = (adminProperties != null) ? adminProperties.password() : null;
        if (password == null || password.isBlank()) {
            password = UUID.randomUUID().toString();
            log.info("No admin password configured. Generated initial admin password: {}", password);
        }

        appUserService.create(username, password, AppRole.ADMIN);
    }
}
