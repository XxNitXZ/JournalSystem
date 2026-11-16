package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import ca.uhn.fhir.context.FhirContext;
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

/** Opt-in live search. Records only transport counts/statuses, never resource contents. */
@EnabledIfSystemProperty(named = "medinexus.fhir.observation-search-test", matches = "true")
class KthFhirObservationSearchIT {
    @Test
    void searchesAndMapsOnlyRequestedPatientObservations() {
        String patientId = System.getProperty("medinexus.fhir.observation-search-patient-id");
        assertThat(patientId != null && patientId.matches("[A-Za-z0-9.-]{1,64}"))
                .as("a valid logical Patient ID must be explicitly configured").isTrue();

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                        FhirObservationClient.class, ObservationMapper.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IGenericClient client = context.getBean(IGenericClient.class);
                    URI base = URI.create(client.getServerBase());
                    String relativeSubject = "Patient/" + patientId;
                    String absoluteSubject = client.getServerBase() + "/" + relativeSubject;
                    List<String> methods = new CopyOnWriteArrayList<>();
                    List<Integer> statuses = new CopyOnWriteArrayList<>();
                    client.registerInterceptor(new IClientInterceptor() {
                        @Override
                        public void interceptRequest(IHttpRequest request) {
                            assertThat(request.getHttpVerbName()).isEqualTo("GET");
                            URI target = URI.create(request.getUri());
                            boolean sameServer = base.getScheme().equalsIgnoreCase(target.getScheme())
                                    && base.getHost().equalsIgnoreCase(target.getHost())
                                    && base.getPort() == target.getPort()
                                    && target.getUserInfo() == null && target.getFragment() == null
                                    && (base.getPath().equals(target.getPath())
                                        || target.getPath().startsWith(base.getPath() + "/"))
                                    && target.normalize().equals(target) && !target.getRawPath().contains("%");
                            assertThat(sameServer).as("every request stays within the configured FHIR server").isTrue();
                            if (methods.isEmpty()) {
                                assertThat(target.getPath().equals(base.getPath() + "/Observation"))
                                        .as("first request targets Observation search").isTrue();
                                String query = URLDecoder.decode(target.getRawQuery(), StandardCharsets.UTF_8);
                                List<String> parameters = Arrays.asList(query.split("&"));
                                assertThat(parameters.contains("subject=" + relativeSubject)
                                        && parameters.contains("_count=100"))
                                        .as("search uses the requested typed Patient reference and page size").isTrue();
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
                                        Bundle page = context.getBean(FhirContext.class).newJsonParser()
                                                .parseResource(Bundle.class, reader);
                                        long components = page.getEntry().stream()
                                                .filter(entry -> entry.getResource() instanceof Observation o && o.hasComponent()).count();
                                        long mapperRejected = page.getEntry().stream().filter(entry -> {
                                            if (!(entry.getResource() instanceof Observation o)) return true;
                                            try { context.getBean(ObservationMapper.class).toDto(o); return false; }
                                            catch (IllegalArgumentException exception) { return true; }
                                        }).count();
                                        var types = page.getEntry().stream()
                                                .filter(entry -> entry.getResource() instanceof Observation)
                                                .map(entry -> (Observation) entry.getResource())
                                                .map(o -> o.hasValue() ? o.getValue().fhirType() : "absent")
                                                .collect(java.util.stream.Collectors.groupingBy(type -> type,
                                                        java.util.TreeMap::new, java.util.stream.Collectors.counting()));
                                        long nextLinks = page.getLink().stream().filter(link -> "next".equals(link.getRelation())).count();
                                        System.out.println("Observation page structure: entries=" + page.getEntry().size()
                                                + "; nextLinks=" + nextLinks + "; componentObservations=" + components
                                                + "; mapperRejections=" + mapperRejected + "; valueTypes=" + types);
                                    }
                                } catch (java.io.IOException | RuntimeException exception) {
                                    throw new AssertionError("Unable to inspect search response structure safely");
                                }
                            }
                        }
                    });

                    java.util.List<com.medinexus.medicore.medicalrecord.dto.ObservationDto> results;
                    try {
                        var search = context.getBean(FhirObservationClient.class).findByPatientId(patientId);
                        results = search.observations();
                        System.out.println("Observation search completeness: complete=" + search.complete()
                                + "; excludedCount=" + search.excludedCount());
                    } finally {
                        System.out.println("Observation search transport: getCount=" + methods.size() + "; statuses=" + statuses);
                    }
                    // Shared server counts may change; no exact result or page count is assumed.
                    assertThat(!results.isEmpty()).as("search returns observations").isTrue();
                    Set<String> validStatuses = Set.of("registered", "preliminary", "final", "amended",
                            "corrected", "cancelled", "entered-in-error", "unknown");
                    for (var dto : results) {
                        assertThat(dto.id() != null && dto.id().matches("[A-Za-z0-9.-]{1,64}"))
                                .as("mapped logical Observation ID is valid").isTrue();
                        assertThat(dto.status() != null && validStatuses.contains(dto.status()))
                                .as("mapped status is valid").isTrue();
                        assertThat(dto.code() != null && (nonblank(dto.code().text()) || dto.code().coding().stream()
                                .anyMatch(coding -> nonblank(coding.code()) || nonblank(coding.display()))))
                                .as("mapped observation code is populated").isTrue();
                        assertThat(dto.subject() != null && (relativeSubject.equals(dto.subject().reference())
                                || absoluteSubject.equals(dto.subject().reference())))
                                .as("every mapped subject identifies the requested Patient").isTrue();
                    }
                    assertThat(methods).isNotEmpty().containsOnly("GET").hasSizeLessThanOrEqualTo(10);
                    assertThat(statuses).hasSameSizeAs(methods).containsOnly(200);
                    System.out.println("Observation search verification: dtoCount=" + results.size()
                            + "; getCount=" + methods.size() + "; additionalPageRequests=" + (methods.size() - 1));
                });
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }
}
