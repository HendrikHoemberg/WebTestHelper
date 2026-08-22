package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.checks.CheckEngine;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The checks module holds no Spring beans (spec 5.1), so the container learns about the
 * catalog here — in one place, from the registry's own list (deviation D15).
 */
@Configuration
class CheckEngineConfiguration {

    @Bean
    CheckRegistry checkRegistry() {
        return CheckRegistry.standard();
    }

    @Bean
    CheckEngine checkEngine(CheckRegistry registry) {
        return new CheckEngine(registry);
    }
}