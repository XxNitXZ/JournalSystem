package com.medinexus.medicore.fhir.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IRestfulClientFactory;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;

/** FHIR client configuration; no database and no network access. */
class FhirClientConfigTest {

    private static final String KTH_FHIR_BASE_URL = "https://hapi-fhir.app.cloud.cbh.kth.se/fhir";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FhirClientConfig.class);

    private ApplicationContextRunner withBaseUrl(String baseUrl) {
        return contextRunner.withPropertyValues(
                "medinexus.fhir.base-url=" + baseUrl,
                "medinexus.fhir.connect-timeout=2s",
                "medinexus.fhir.socket-timeout=5s",
                "medinexus.fhir.connection-request-timeout=3s");
    }

    @Test
    void applicationPropertiesConfigureR4ClientWithoutImplicitMetadataRequests() {
        contextRunner.withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
            assertThat(context).hasNotFailed();
            FhirContext fhirContext = context.getBean(FhirContext.class);
            assertThat(fhirContext.getVersion().getVersion()).isEqualTo(FhirVersionEnum.R4);

            IRestfulClientFactory clientFactory = fhirContext.getRestfulClientFactory();
            assertThat(clientFactory.getServerValidationMode()).isEqualTo(ServerValidationModeEnum.NEVER);
            assertThat(clientFactory.getConnectTimeout()).isEqualTo(10_000);
            assertThat(clientFactory.getSocketTimeout()).isEqualTo(30_000);
            assertThat(clientFactory.getConnectionRequestTimeout()).isEqualTo(10_000);
        });
    }

    @Test
    void baseUrlDefaultsToKthServer() {
        assumeTrue(System.getenv("FHIR_BASE_URL") == null, "FHIR_BASE_URL is set in this environment");
        contextRunner.withInitializer(new ConfigDataApplicationContextInitializer()).run(context ->
                assertThat(context.getBean(IGenericClient.class).getServerBase()).isEqualTo(KTH_FHIR_BASE_URL));
    }

    @ParameterizedTest
    @CsvSource({
            "http://localhost:8080/fhir/, http://localhost:8080/fhir",
            "http://127.0.0.1:9090, http://127.0.0.1:9090",
            "https://fhir.example.org/r4, https://fhir.example.org/r4"
    })
    void baseUrlIsConfigurable(String configured, String expected) {
        withBaseUrl(configured).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IGenericClient.class).getServerBase()).isEqualTo(expected);
            IRestfulClientFactory clientFactory = context.getBean(FhirContext.class).getRestfulClientFactory();
            assertThat(clientFactory.getConnectTimeout()).isEqualTo(2_000);
            assertThat(clientFactory.getSocketTimeout()).isEqualTo(5_000);
            assertThat(clientFactory.getConnectionRequestTimeout()).isEqualTo(3_000);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "ftp://fhir.example.org/fhir",
            "fhir.example.org/fhir",
            "http:///fhir",
            "https://fhir.example.org/fhir?_format=json"
    })
    void invalidBaseUrlFailsAtStartup(String baseUrl) {
        withBaseUrl(baseUrl).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("medinexus.fhir.base-url");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect-timeout", "socket-timeout", "connection-request-timeout"})
    void nonPositiveTimeoutFailsAtStartup(String timeout) {
        withBaseUrl("http://localhost:8080/fhir")
                .withPropertyValues("medinexus.fhir." + timeout + "=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("medinexus.fhir." + timeout);
                });
    }

    @Test
    void startsWithoutContactingAnUnreachableServer() {
        // ".invalid" can never resolve: startup only succeeds because no request is made.
        withBaseUrl("http://fhir-server.invalid/fhir").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IGenericClient.class).getServerBase()).isEqualTo("http://fhir-server.invalid/fhir");
        });
    }
}
