package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.ConditionMapper;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;

@EnabledIfSystemProperty(named = "medinexus.fhir.condition-search-test", matches = "true")
class KthFhirConditionSearchIT {
    @Test
    void searchesAndMapsRequestedPatientConditions() {
        String id = System.getProperty("medinexus.fhir.condition-search-patient-id", "0a48619e-4751-6df5-4304-7f88a6526a33");
        assertThat(id.matches("[A-Za-z0-9.-]{1,64}")).as("valid logical Patient ID").isTrue();
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                        FhirConditionClient.class, ConditionMapper.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(IGenericClient.class);
                    URI base = URI.create(client.getServerBase());
                    List<String> methods = new CopyOnWriteArrayList<>();
                    List<Integer> statuses = new CopyOnWriteArrayList<>();
                    List<Condition> resources = new CopyOnWriteArrayList<>();
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
                                assertThat(uri.getPath().equals(base.getPath() + "/Condition")).isTrue();
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
                                            assertThat(entry.getResource() instanceof Condition).as("Condition search entries").isTrue();
                                            resources.add((Condition) entry.getResource());
                                        }
                                    }
                                } catch (java.io.IOException | RuntimeException exception) {
                                    throw new AssertionError("Unable to inspect Condition search structure safely");
                                }
                            }
                        }
                    });
                    var results = context.getBean(FhirConditionClient.class).findByPatientId(id);
                    assertThat(!results.isEmpty()).as("nonempty live Condition results").isTrue();
                    assertThat(results.size()).isEqualTo(resources.size());
                    for (int i = 0; i < results.size(); i++) {
                        var dto = results.get(i);
                        assertThat(dto.subject() != null && ("Patient/" + id).equals(dto.subject().reference())
                                || dto.subject() != null && (client.getServerBase() + "/Patient/" + id).equals(dto.subject().reference()))
                                .as("correct Patient reference").isTrue();
                        KthFhirConditionReadIT.verifyMapping(resources.get(i), dto);
                    }
                    assertThat(methods).isNotEmpty().containsOnly("GET").hasSizeLessThanOrEqualTo(10);
                    assertThat(statuses).hasSameSizeAs(methods).containsOnly(200);
                    System.out.println("Condition search verification: conditionCount=" + results.size() + "; getCount=" + methods.size()
                            + "; additionalPageRequests=" + (methods.size() - 1));
                });
    }
}
