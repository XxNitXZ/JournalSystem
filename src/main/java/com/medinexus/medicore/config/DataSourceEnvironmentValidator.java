package com.medinexus.medicore.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Stops application startup with a clear message when the database connection
 * settings (DB_URL, DB_USERNAME, DB_PASSWORD) are not provided.
 *
 * <p>The check runs only when Spring Boot configures a DataSource, so test slices
 * without a database (for example {@code @WebMvcTest}) are not affected. Only the
 * names of missing variables are reported; configured values are never logged.
 */
@Component
public class DataSourceEnvironmentValidator implements BeanPostProcessor {

    /** Datasource property -> environment variable that supplies it. */
    private static final Map<String, String> REQUIRED_SETTINGS = new LinkedHashMap<>();

    static {
        REQUIRED_SETTINGS.put("spring.datasource.url", "DB_URL");
        REQUIRED_SETTINGS.put("spring.datasource.username", "DB_USERNAME");
        REQUIRED_SETTINGS.put("spring.datasource.password", "DB_PASSWORD");
    }

    private final Environment environment;

    public DataSourceEnvironmentValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSourceProperties) {
            List<String> missingVariables = findMissingVariables();
            if (!missingVariables.isEmpty()) {
                throw new MissingDataSourceConfigurationException(missingVariables);
            }
        }
        return bean;
    }

    private List<String> findMissingVariables() {
        List<String> missing = new ArrayList<>();
        REQUIRED_SETTINGS.forEach((property, variable) -> {
            if (!isConfigured(property)) {
                missing.add(variable);
            }
        });
        return missing;
    }

    private boolean isConfigured(String property) {
        try {
            return StringUtils.hasText(environment.getProperty(property));
        }
        catch (IllegalArgumentException unresolvedPlaceholder) {
            // ${DB_...} could not be resolved: the variable is not set.
            return false;
        }
    }
}
