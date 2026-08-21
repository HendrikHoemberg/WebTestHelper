package dev.hendrikhoemberg.webtesthelper;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(WebtesthelperApplication.class);

    @Test
    void modulesRespectTheirDeclaredDependencies() {
        MODULES.verify();
    }

    @Test
    void moduleStructureIsPrintable() {
        MODULES.forEach(module -> System.out.println(module.getDisplayName()));
    }
}
