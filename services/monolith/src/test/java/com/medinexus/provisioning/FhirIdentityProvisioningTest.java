package com.medinexus.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import com.medinexus.medicore.auth.service.AccountFhirIdentityService;

class FhirIdentityProvisioningTest {
    private final AccountFhirIdentityService service = mock(AccountFhirIdentityService.class);
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    @SuppressWarnings("unchecked")
    private final Supplier<ConfigurableApplicationContext> start = mock(Supplier.class);
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream output = new PrintStream(bytes);

    @Test
    void disabledByDefaultWithoutStartingContext() {
        assertThat(run(new MockEnvironment())).isEqualTo(2);
        assertThat(bytes.toString()).contains("disabled");
        verifyNoInteractions(start, service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "TRUE", "yes", "1", ""})
    void requiresExplicitTrue(String enabled) {
        assertThat(run(valid().withProperty("medinexus.fhir.provisioning.enabled", enabled))).isEqualTo(2);
        verifyNoInteractions(start, service);
    }

    @Test
    void invokesServiceExactlyOnceAndClosesContext() {
        when(start.get()).thenReturn(context);
        when(context.getBean(AccountFhirIdentityService.class)).thenReturn(service);
        assertThat(run(valid())).isZero();
        verify(start, times(1)).get();
        verify(service, times(1)).assign(42L, "synthetic-1");
        verifyNoMoreInteractions(service);
        verify(context).close();
        assertThat(bytes.toString()).isEqualTo("Provisioning completed successfully." + System.lineSeparator());
    }

    @Test
    void missingConfigurationDoesNotStartAnything() {
        for (String missing : new String[] {"account-id", "fhir-id"}) {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("medinexus.fhir.provisioning.enabled", "true");
            if (missing.equals("account-id")) environment.withProperty("medinexus.fhir.provisioning.fhir-id", "synthetic-1");
            else environment.withProperty("medinexus.fhir.provisioning.account-id", "42");
            assertThat(run(environment)).isEqualTo(2);
        }
        verifyNoInteractions(start, service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number", "9223372036854775808"})
    void invalidAccountIdDoesNotStartAnything(String value) {
        assertThat(run(valid().withProperty("medinexus.fhir.provisioning.account-id", value))).isEqualTo(2);
        verifyNoInteractions(start, service);
        assertThat(bytes.toString()).doesNotContain(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "Patient/1", "https://example.test/Patient/1", "id_name",
            "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklm"})
    void invalidFhirIdDoesNotStartAnything(String value) {
        assertThat(run(valid().withProperty("medinexus.fhir.provisioning.fhir-id", value))).isEqualTo(2);
        verifyNoInteractions(start, service);
    }

    @Test
    void serviceFailureIsSanitizedAndContextClosed() {
        when(start.get()).thenReturn(context);
        when(context.getBean(AccountFhirIdentityService.class)).thenReturn(service);
        doThrow(new IllegalStateException("SECRET password synthetic-1"))
                .when(service).assign(42L, "synthetic-1");
        assertThat(run(valid())).isEqualTo(1);
        verify(context).close();
        verify(service, times(1)).assign(42L, "synthetic-1");
        assertThat(bytes.toString()).contains("Provisioning failed").doesNotContain("SECRET", "password", "synthetic-1");
    }

    @Test
    void startupFailureIsSanitized() {
        when(start.get()).thenThrow(new IllegalStateException("SECRET JDBC URL credentials"));
        assertThat(run(valid())).isEqualTo(1);
        verifyNoInteractions(service);
        assertThat(bytes.toString()).contains("Provisioning failed").doesNotContain("SECRET", "JDBC", "credentials");
    }

    @Test
    void applicationForcesNonWebModeAndQuietLogging() {
        MockEnvironment environment = valid().withProperty("spring.main.web-application-type", "servlet")
                .withProperty("logging.level.root", "DEBUG");
        var app = FhirIdentityProvisioning.application(environment);
        assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(environment.getProperty("logging.level.root")).isEqualTo("OFF");
        assertThat(app.getAllSources()).containsExactly(FhirIdentityProvisioning.ProvisioningConfiguration.class);
    }

    private MockEnvironment valid() {
        return new MockEnvironment().withProperty("medinexus.fhir.provisioning.enabled", "true")
                .withProperty("medinexus.fhir.provisioning.account-id", "42")
                .withProperty("medinexus.fhir.provisioning.fhir-id", "synthetic-1");
    }

    private int run(MockEnvironment environment) {
        return FhirIdentityProvisioning.run(environment, start, output);
    }
}
