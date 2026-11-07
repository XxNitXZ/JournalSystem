package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hl7.fhir.r4.model.Organization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.OrganizationMapper;
import com.medinexus.medicore.organization.dto.OrganizationDto;

import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;

/**
 * Explicitly enabled, read-only check of an existing, independently confirmed synthetic Organization:
 * -Dmedinexus.fhir.organization-read-test=true -Dmedinexus.fhir.organization-id=...
 * Exactly one GET; never creates, updates or deletes resources and never prints Organization content.
 */
@EnabledIfSystemProperty(named = "medinexus.fhir.organization-read-test", matches = "true")
class KthFhirOrganizationReadIT {

    @Test
    void readsAndMapsConfirmedSyntheticOrganization() {
        String id = System.getProperty("medinexus.fhir.organization-id");
        assertThat(id != null && id.matches("[A-Za-z0-9.-]{1,64}"))
                .as("Supply the logical ID of a confirmed synthetic Organization").isTrue();

        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                        FhirOrganizationClient.class, OrganizationMapper.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IGenericClient client = context.getBean(IGenericClient.class);
                    URI expected = URI.create(client.getServerBase() + "/Organization/" + id);
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

                    Organization organization = context.getBean(FhirOrganizationClient.class).readOrganization(id);
                    OrganizationDto dto = context.getBean(OrganizationMapper.class).toDto(organization);

                    assertThat(methods).containsExactly("GET");
                    assertThat(statuses).containsExactly(200);
                    assertThat("Organization".equals(organization.fhirType())).isTrue();
                    assertThat(id.equals(dto.id())).as("requested logical ID preserved").isTrue();
                    assertThat(dto.versionId() != null).as("server version available for safe updates").isTrue();
                    assertThat(dto.name() != null || !dto.identifiers().isEmpty()).as("org-1 satisfied").isTrue();
                    assertThat(Objects.equals(organization.hasPartOf() ? organization.getPartOf().getReference() : null,
                            dto.partOf() == null ? null : dto.partOf().reference())).as("partOf reference preserved").isTrue();
                    assertThat(dto.telecoms().size()).isEqualTo(organization.getTelecom().size());
                    assertThat(dto.addresses().size()).isEqualTo(organization.getAddress().size());
                    System.out.println("Organization read verification: organizationCount=1; getCount=" + methods.size());
                });
    }
}
