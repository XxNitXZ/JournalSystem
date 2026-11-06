package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.ObservationMapper;

import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;

/**
 * Explicitly enabled read of an existing Observation selected for an explicitly enabled read.
 * Never discovers arbitrary observations, writes resources, or prints observation content.
 */
@EnabledIfSystemProperty(named = "medinexus.fhir.observation-read-test", matches = "true")
class KthFhirObservationReadIT {

    @Test
    void readsAndMapsExistingObservation() {
        String id = System.getProperty("medinexus.fhir.observation-id", "10015");
        assertThat(id).as("Supply a logical Observation ID")
                .isNotNull().matches("[A-Za-z0-9.-]{1,64}");

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                        FhirObservationClient.class, ObservationMapper.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IGenericClient client = context.getBean(IGenericClient.class);
                    URI expected = URI.create(client.getServerBase() + "/Observation/" + id);
                    List<Integer> statuses = new CopyOnWriteArrayList<>();
                    List<String> methods = new CopyOnWriteArrayList<>();
                    client.registerInterceptor(new IClientInterceptor() {
                        @Override
                        public void interceptRequest(IHttpRequest request) {
                            assertThat(request.getHttpVerbName()).isEqualTo("GET");
                            assertThat(URI.create(request.getUri())).isEqualTo(expected);
                            methods.add(request.getHttpVerbName());
                        }

                        @Override
                        public void interceptResponse(IHttpResponse response) {
                            statuses.add(response.getStatus());
                        }
                    });

                    var observation = context.getBean(FhirObservationClient.class).readObservation(id);
                    // Structural metadata only: never print the resource, subject, codings or values.
                    String valueType = observation.hasValue() ? observation.getValue().fhirType() : "absent";
                    System.out.println("Observation verification: valueType=" + valueType
                            + "; componentsPresent=" + observation.hasComponent());
                    var dto = context.getBean(ObservationMapper.class).toDto(observation);

                    assertThat(methods).containsExactly("GET");
                    assertThat(statuses).containsExactly(200);
                    assertThat(observation.fhirType()).isEqualTo("Observation");
                    assertThat(dto.id()).isEqualTo(id);
                    assertThat(observation.hasStatus()).as("required status exists").isTrue();
                    assertThat(observation.hasCode()).as("required code exists").isTrue();
                    assertThat(dto.status()).isEqualTo(observation.getStatus().toCode());
                    assertThat(dto.code()).isNotNull();
                    assertThat(dto.code().coding()).hasSize(observation.getCode().getCoding().size());
                    assertThat(java.util.Objects.equals(dto.code().text(), observation.getCode().getText()))
                            .as("code text preserved").isTrue();
                    if (observation.hasSubject()) {
                        assertThat(dto.subject()).isNotNull();
                        assertThat(java.util.Objects.equals(dto.subject().reference(), observation.getSubject().getReference()))
                                .as("subject reference preserved").isTrue();
                    } else {
                        assertThat(dto.subject()).isNull();
                    }
                    if (observation.hasValue()) {
                        assertThat(dto.value()).isNotNull();
                        String expectedType = switch (valueType) {
                            case "CodeableConcept" -> "CODEABLE_CONCEPT";
                            case "dateTime" -> "DATE_TIME";
                            default -> valueType.toUpperCase(java.util.Locale.ROOT);
                        };
                        assertThat(dto.value().type().name()).isEqualTo(expectedType);
                        assertThat(dto.value().data()).isNotNull();
                    } else {
                        assertThat(dto.value()).isNull();
                    }
                    if (observation.hasDataAbsentReason()) assertThat(dto.dataAbsentReason()).isNotNull();
                    else assertThat(dto.dataAbsentReason()).isNull();
                });
    }
}
