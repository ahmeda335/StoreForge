package dev.storeforge.shared.release;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Profile "migrate": Flyway has already run during startup (as the owner role); this ends the process
 * with exit code 0, so a deploy script or orchestrator can treat migrations as a job that succeeds or
 * fails before any new code serves traffic.
 */
@Component
@Profile("migrate")
@Slf4j
@RequiredArgsConstructor
class MigrationRunner implements ApplicationRunner {

    private final ConfigurableApplicationContext context;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Migrations applied; exiting (profile migrate)");
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
