package dev.storeforge.shared.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Logback starts before Spring, so the OpenTelemetry appender in logback-spring.xml has no SDK to
 * send to yet. Once the context has created the OpenTelemetry SDK, this hands it to the appender.
 */
@Component
class OpenTelemetryLogbackInstaller implements SmartInitializingSingleton {

    private final OpenTelemetry openTelemetry;

    OpenTelemetryLogbackInstaller(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
