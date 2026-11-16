package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode;
import org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction;
import org.hl7.fhir.r4.model.Enumerations.FHIRVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;

import com.medinexus.medicore.fhir.config.FhirClientConfig;

/**
 * Opt-in, read-only connectivity check against the real external FHIR server
 * (KTH by default, or {@code FHIR_BASE_URL}).
 *
 * <p>Not part of the normal build: the {@code IT} suffix keeps it out of
 * Surefire's default test selection, and it is skipped unless the system
 * property {@code medinexus.fhir.connectivity-test=true} is set. Run it with:
 *
 * <pre>
 * mvnw test -Dtest=KthFhirServerConnectivityIT -Dmedinexus.fhir.connectivity-test=true
 * </pre>
 *
 * <p>Only the FHIR beans are started (no database). The test sends exactly one
 * request, {@code GET [base]/metadata}, and asserts only stable facts: FHIR
 * 4.0.1 and read/search support for the core resource types. It never
 * creates, updates or deletes resources.
 */
@EnabledIfSystemProperty(named = "medinexus.fhir.connectivity-test", matches = "true")
class KthFhirServerConnectivityIT {

    private static final List<String> CORE_RESOURCE_TYPES =
            List.of("Patient", "Practitioner", "Observation", "Condition", "Encounter");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(FhirClientConfig.class, FhirServerClient.class, FhirRequestExecutor.class);

    @Test
    void capabilityStatementDeclaresR4AndReadAndSearchForCoreResources() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            RequestRecorder requests = new RequestRecorder();
            context.getBean(IGenericClient.class).registerInterceptor(requests);

            CapabilityStatement capabilityStatement = context.getBean(FhirServerClient.class).fetchCapabilityStatement();

            assertThat(requests.sent).as("exactly one read-only request").hasSize(1);
            assertThat(requests.sent.get(0)).startsWith("GET ").endsWith("/metadata");

            assertThat(capabilityStatement.getFhirVersion()).isEqualTo(FHIRVersion._4_0_1);
            CapabilityStatementRestComponent rest = capabilityStatement.getRestFirstRep();
            assertThat(rest.getMode()).isEqualTo(RestfulCapabilityMode.SERVER);

            for (String resourceType : CORE_RESOURCE_TYPES) {
                CapabilityStatementRestResourceComponent resource = rest.getResource().stream()
                        .filter(candidate -> resourceType.equals(candidate.getType()))
                        .findFirst()
                        .orElse(null);
                assertThat(resource).as("%s is declared", resourceType).isNotNull();
                assertThat(resource.getInteraction())
                        .as("%s interactions", resourceType)
                        .extracting(interaction -> interaction.getCode())
                        .contains(TypeRestfulInteraction.READ, TypeRestfulInteraction.SEARCHTYPE);
            }
        });
    }

    /** Records method and path (never query strings or bodies) of every request sent. */
    private static final class RequestRecorder implements IClientInterceptor {

        private final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public void interceptRequest(IHttpRequest request) {
            sent.add(request.getHttpVerbName() + " " + URI.create(request.getUri()).getPath());
        }

        @Override
        public void interceptResponse(IHttpResponse response) {
            // nothing to record
        }
    }
}
