package com.medinexus.medicore.config;

import java.util.List;

/**
 * Thrown at startup when required database environment variables are missing.
 * Contains only variable names, never their values.
 */
public class MissingDataSourceConfigurationException extends IllegalStateException {

    private final List<String> missingVariables;

    public MissingDataSourceConfigurationException(List<String> missingVariables) {
        super("Missing database configuration: " + String.join(", ", missingVariables));
        this.missingVariables = List.copyOf(missingVariables);
    }

    public List<String> getMissingVariables() {
        return missingVariables;
    }
}
