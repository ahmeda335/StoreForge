package dev.storeforge;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Fails the build when a module reaches into another module's internals or when modules form a
 * dependency cycle. This is what keeps the modular monolith modular (ADR-001).
 */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(StoreForgeApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        modules.verify();
    }
}
