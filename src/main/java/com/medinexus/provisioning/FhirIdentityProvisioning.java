package com.medinexus.provisioning;

import java.io.PrintStream;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.AccountFhirIdentityService;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;
import com.medinexus.medicore.fhir.client.FhirRequestExecutor;
import com.medinexus.medicore.fhir.config.FhirClientConfig;

/** Local operator command, deliberately outside MediNexusApplication's component scan. */
public final class FhirIdentityProvisioning {
    private static final String PREFIX = "medinexus.fhir.provisioning.";

    private FhirIdentityProvisioning() {
    }

    public static void main(String[] args) {
        int result;
        try {
            ConfigurableEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
            SpringApplication application = application(environment);
            result = run(environment, application::run, System.out);
        } catch (RuntimeException exception) {
            System.out.println("Provisioning failed; configuration could not be read.");
            result = 2;
        }
        System.exit(result);
    }

    static SpringApplication application(ConfigurableEnvironment environment) {
        // Do not expose framework failure reports, SQL errors, configuration values or FHIR details.
        environment.getPropertySources().addFirst(new MapPropertySource("provisioningSafety", Map.of(
                "spring.main.web-application-type", "none",
                "spring.main.banner-mode", "off",
                "logging.level.root", "OFF",
                "logging.level.org.springframework", "OFF",
                "logging.level.org.hibernate", "OFF",
                "logging.level.ca.uhn", "OFF",
                "logging.level.com.medinexus", "OFF",
                "debug", "false", "trace", "false")));
        SpringApplication application = new SpringApplication(ProvisioningConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setEnvironment(environment);
        application.setAddCommandLineProperties(false);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        return application;
    }

    static int run(Environment environment, Supplier<ConfigurableApplicationContext> start, PrintStream output) {
        if (!"true".equals(environment.getProperty(PREFIX + "enabled"))) {
            output.println("Provisioning disabled; no action taken.");
            return 2;
        }
        Long accountId;
        String fhirId;
        try {
            accountId = Long.valueOf(environment.getRequiredProperty(PREFIX + "account-id"));
            fhirId = environment.getRequiredProperty(PREFIX + "fhir-id");
            if (accountId <= 0 || !fhirId.matches("[A-Za-z0-9.-]{1,64}")) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            output.println("Provisioning configuration invalid; positive account-id and logical fhir-id are required.");
            return 2;
        }
        try (ConfigurableApplicationContext context = start.get()) {
            context.getBean(AccountFhirIdentityService.class).assign(accountId, fhirId);
        } catch (RuntimeException exception) {
            output.println("Provisioning failed; verify configuration, connectivity and association eligibility before retrying.");
            return 1;
        }
        output.println("Provisioning completed successfully.");
        return 0;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    @Import({AccountFhirIdentityService.class, FhirClientConfig.class,
            FhirRequestExecutor.class, FhirPatientClient.class, FhirPractitionerClient.class})
    static class ProvisioningConfiguration {
    }
}
