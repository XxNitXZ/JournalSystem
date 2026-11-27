package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hl7.fhir.r4.model.Encounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.EncounterMapper;
import com.medinexus.medicore.medicalrecord.dto.EncounterDto;

import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;

/**
 * Explicitly enabled, read-only check of an existing, independently confirmed synthetic Encounter:
 * -Dmedinexus.fhir.encounter-read-test=true -Dmedinexus.fhir.encounter-id=...
 * Never discovers arbitrary encounters, writes resources, or prints clinical content.
 */
@EnabledIfSystemProperty(named = "medinexus.fhir.encounter-read-test", matches = "true")
class KthFhirEncounterReadIT {

    @Test
    void readsAndMapsConfirmedSyntheticEncounter() {
        String id = System.getProperty("medinexus.fhir.encounter-id");
        assertThat(id != null && id.matches("[A-Za-z0-9.-]{1,64}"))
                .as("Supply the logical ID of a confirmed synthetic Encounter").isTrue();

        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                        FhirEncounterClient.class, EncounterMapper.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IGenericClient client = context.getBean(IGenericClient.class);
                    URI expected = URI.create(client.getServerBase() + "/Encounter/" + id);
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

                    Encounter encounter = context.getBean(FhirEncounterClient.class).readEncounter(id);
                    EncounterDto dto = context.getBean(EncounterMapper.class).toDto(encounter);

                    assertThat(methods).containsExactly("GET");
                    assertThat(statuses).containsExactly(200);
                    assertThat("Encounter".equals(encounter.fhirType())).isTrue();
                    assertThat(id.equals(dto.id())).as("requested logical ID preserved").isTrue();
                    assertThat(dto.status() != null && dto.encounterClass() != null).as("required fields mapped").isTrue();
                    assertThat(Objects.equals(encounter.hasSubject() ? encounter.getSubject().getReference() : null,
                            dto.subject() == null ? null : dto.subject().reference())).as("subject reference preserved").isTrue();
                    assertThat(dto.participants().size()).isEqualTo(encounter.getParticipant().size());
                    assertThat(dto.locations().size()).isEqualTo(encounter.getLocation().size());
                    System.out.println("Encounter read verification: encounterCount=1; getCount=" + methods.size());
                });
    }
}
