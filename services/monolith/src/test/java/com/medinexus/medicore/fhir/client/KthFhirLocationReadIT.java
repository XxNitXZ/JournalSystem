package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hl7.fhir.r4.model.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.LocationMapper;
import com.medinexus.medicore.location.dto.LocationDto;

import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;

/**
 * Explicitly enabled, read-only check of an existing, independently confirmed synthetic Location:
 * -Dmedinexus.fhir.location-read-test=true -Dmedinexus.fhir.location-id=...
 * Exactly one GET; never creates, updates or deletes resources and never prints Location content.
 */
@EnabledIfSystemProperty(named = "medinexus.fhir.location-read-test", matches = "true")
class KthFhirLocationReadIT {

    @Test
    void readsAndMapsConfirmedSyntheticLocation() {
        String id = System.getProperty("medinexus.fhir.location-id");
        assertThat(id != null && id.matches("[A-Za-z0-9.-]{1,64}"))
                .as("Supply the logical ID of a confirmed synthetic Location").isTrue();

        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                        FhirLocationClient.class, LocationMapper.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IGenericClient client = context.getBean(IGenericClient.class);
                    URI expected = URI.create(client.getServerBase() + "/Location/" + id);
                    List<String> methods = new CopyOnWriteArrayList<>();
                    List<Integer> statuses = new CopyOnWriteArrayList<>();
                    client.registerInterceptor(new IClientInterceptor() {
                        @Override
                        public void interceptRequest(IHttpRequest request) {
                            assertThat(request.getHttpVerbName()).isEqualTo("GET");
                            assertThat(expected.equals(URI.create(request.getUri()))).as("expected read endpoint").isTrue();
                            methods.add(request.getHttpVerbName());
                        }

                        @Override
                        public void interceptResponse(IHttpResponse response) {
                            statuses.add(response.getStatus());
                        }
                    });

                    Location location = context.getBean(FhirLocationClient.class).readLocation(id);
                    LocationDto dto = context.getBean(LocationMapper.class).toDto(location);

                    assertThat(methods).containsExactly("GET");
                    assertThat(statuses).containsExactly(200);
                    assertThat("Location".equals(location.fhirType())).isTrue();
                    assertThat(id.equals(dto.id())).as("requested logical ID preserved").isTrue();
                    assertThat(dto.versionId() != null).as("server version available for safe updates").isTrue();
                    assertThat(Objects.equals(location.hasManagingOrganization() ? location.getManagingOrganization().getReference() : null,
                            dto.managingOrganization() == null ? null : dto.managingOrganization().reference()))
                            .as("managingOrganization reference preserved").isTrue();
                    assertThat(Objects.equals(location.hasPartOf() ? location.getPartOf().getReference() : null,
                            dto.partOf() == null ? null : dto.partOf().reference())).as("partOf reference preserved").isTrue();
                    assertThat(Objects.equals(location.hasPosition() ? location.getPosition().getLatitude() : null,
                            dto.position() == null ? null : dto.position().latitude())).as("exact latitude").isTrue();
                    assertThat(dto.telecoms().size()).isEqualTo(location.getTelecom().size());
                    System.out.println("Location read verification: locationCount=1; getCount=" + methods.size());
                });
    }
}
