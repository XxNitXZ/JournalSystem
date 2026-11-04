package com.medinexus.medicore.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns {@link MissingDataSourceConfigurationException} into Spring Boot's
 * "APPLICATION FAILED TO START" report instead of a long stack trace.
 * Registered in META-INF/spring.factories.
 */
public class MissingDataSourceConfigurationFailureAnalyzer
        extends AbstractFailureAnalyzer<MissingDataSourceConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingDataSourceConfigurationException cause) {
        String description = "The database connection is not configured. Missing environment variable(s): "
                + String.join(", ", cause.getMissingVariables());

        String action = """
                Set DB_URL, DB_USERNAME and DB_PASSWORD before starting MediCore, for example:
                    DB_URL=jdbc:postgresql://127.0.0.1:5432/medinexus_db
                    DB_USERNAME=medinexus
                    DB_PASSWORD=<your local database password>
                See infrastructure/database/README.md, section "Connecting MediCore (Spring Boot)",
                for Windows, macOS and IntelliJ instructions.""";

        return new FailureAnalysis(description, action, cause);
    }
}
