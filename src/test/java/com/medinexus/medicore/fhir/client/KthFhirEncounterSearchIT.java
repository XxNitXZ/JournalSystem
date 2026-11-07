package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.EncounterMapper;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;

/**
 * Explicitly enabled, read-only Encounter search for an independently confirmed synthetic Patient:
 * -Dmedinexus.fhir.encounter-search-test=true -Dmedinexus.fhir.encounter-search-patient-id=...
 * GET requests only, all within the configured server; never writes or prints clinical content.
 */
@EnabledIfSystemProperty(named = "medinexus.fhir.encounter-search-test", matches = "true")
class KthFhirEncounterSearchIT {
    @Test
    void searchesAndMapsConfirmedSyntheticPatientEncounters() {
        String id = System.getProperty("medinexus.fhir.encounter-search-patient-id");
        assertThat(id != null && id.matches("[A-Za-z0-9.-]{1,64}"))
                .as("Supply the logical ID of a confirmed synthetic Patient").isTrue();
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                        FhirEncounterClient.class, EncounterMapper.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(IGenericClient.class);
                    URI base = URI.create(client.getServerBase());
                    List<String> methods = new CopyOnWriteArrayList<>();
                    List<Integer> statuses = new CopyOnWriteArrayList<>();
                    List<Encounter> resources = new CopyOnWriteArrayList<>();
                    client.registerInterceptor(new IClientInterceptor() {
                        @Override
                        public void interceptRequest(IHttpRequest request) {
                            assertThat(request.getHttpVerbName()).isEqualTo("GET");
                            URI uri = URI.create(request.getUri());
                            assertThat(base.getScheme().equalsIgnoreCase(uri.getScheme())
                                    && base.getHost().equalsIgnoreCase(uri.getHost()) && base.getPort() == uri.getPort()
                                    && uri.getUserInfo() == null && uri.getFragment() == null
                                    && (uri.getPath().equals(base.getPath()) || uri.getPath().startsWith(base.getPath() + "/"))
                                    && uri.normalize().equals(uri) && !uri.getRawPath().contains("%"))
                                    .as("GET remains within configured FHIR server").isTrue();
                            if (methods.isEmpty()) {
                                assertThat(uri.getPath().equals(base.getPath() + "/Encounter")).isTrue();
                                var parameters = Arrays.asList(URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8).split("&"));
                                assertThat(parameters.contains("subject=Patient/" + id) && parameters.contains("_count=100"))
                                        .as("typed Patient filter and page size").isTrue();
                            }
                            methods.add(request.getHttpVerbName());
                        }

                        @Override
                        public void interceptResponse(IHttpResponse response) {
                            statuses.add(response.getStatus());
                            if (response.getStatus() == 200) {
                                try {
                                    response.bufferEntity();
                                    try (var reader = response.createReader()) {
                                        Bundle page = context.getBean(FhirContext.class).newJsonParser().parseResource(Bundle.class, reader);
                                        assertThat(page.getType()).isEqualTo(Bundle.BundleType.SEARCHSET);
                                        for (var entry : page.getEntry()) {
                                            assertThat(entry.getResource() instanceof Encounter).as("Encounter search entries").isTrue();
                                            resources.add((Encounter) entry.getResource());
                                        }
                                    }
                                } catch (java.io.IOException | RuntimeException exception) {
                                    throw new AssertionError("Unable to inspect Encounter search structure safely");
                                }
                            }
                        }
                    });
                    var results = context.getBean(FhirEncounterClient.class).findByPatientId(id);
                    assertThat(!results.isEmpty()).as("nonempty live Encounter results").isTrue();
                    assertThat(results.size()).isEqualTo(resources.size());
                    for (int i = 0; i < results.size(); i++) {
                        var dto = results.get(i);
                        var source = resources.get(i);
                        assertThat(dto.subject() != null && (("Patient/" + id).equals(dto.subject().reference())
                                || (client.getServerBase() + "/Patient/" + id).equals(dto.subject().reference())))
                                .as("correct Patient reference").isTrue();
                        assertThat(Objects.equals(source.getIdElement().getIdPart(), dto.id())).as("logical ID preserved").isTrue();
                        assertThat(dto.participants().size()).isEqualTo(source.getParticipant().size());
                        for (int p = 0; p < dto.participants().size(); p++) {
                            var participant = source.getParticipant().get(p);
                            String expected = participant.hasIndividual() ? participant.getIndividual().getReference() : null;
                            var mapped = dto.participants().get(p).individual();
                            assertThat(Objects.equals(expected, mapped == null ? null : mapped.reference()))
                                    .as("participant reference preserved").isTrue();
                        }
                    }
                    assertThat(methods).isNotEmpty().containsOnly("GET").hasSizeLessThanOrEqualTo(10);
                    assertThat(statuses).hasSameSizeAs(methods).containsOnly(200);
                    System.out.println("Encounter search verification: encounterCount=" + results.size() + "; getCount=" + methods.size()
                            + "; additionalPageRequests=" + (methods.size() - 1));
                });
    }
}
