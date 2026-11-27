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
import com.medinexus.medicore.fhir.mapper.PractitionerMapper;

import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;

/**
 * Explicitly enabled read of an existing, independently confirmed synthetic Practitioner.
 * Never discovers arbitrary practitioners, writes resources, or prints practitioner content.
 */
@EnabledIfSystemProperty(named = "medinexus.fhir.practitioner-read-test", matches = "true")
class KthFhirPractitionerReadIT {

    @Test
    void readsAndMapsConfirmedSyntheticPractitioner() {
        String id = System.getProperty("medinexus.fhir.synthetic-practitioner-id");
        assertThat(id).as("Supply a logical ID from a confirmed synthetic dataset")
                .isNotNull().matches("[A-Za-z0-9.-]{1,64}");

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                        FhirPractitionerClient.class, PractitionerMapper.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IGenericClient client = context.getBean(IGenericClient.class);
                    URI expected = URI.create(client.getServerBase() + "/Practitioner/" + id);
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

                    var practitioner = context.getBean(FhirPractitionerClient.class).readPractitioner(id);
                    var dto = context.getBean(PractitionerMapper.class).toDto(practitioner);

                    assertThat(methods).containsExactly("GET");
                    assertThat(statuses).containsExactly(200);
                    assertThat(practitioner.fhirType()).isEqualTo("Practitioner");
                    assertThat(dto.id()).isEqualTo(id);
                    assertThat(dto.identifiers()).isNotNull();
                    assertThat(dto.names()).isNotNull();
                });
    }
}
