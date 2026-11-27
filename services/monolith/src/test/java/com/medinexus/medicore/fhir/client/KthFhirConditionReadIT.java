package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hl7.fhir.r4.model.Age;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Range;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.ConditionMapper;
import com.medinexus.medicore.medicalrecord.dto.ConditionDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;

import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;

@EnabledIfSystemProperty(named = "medinexus.fhir.condition-read-test", matches = "true")
class KthFhirConditionReadIT {
    @Test
    void readsAndMapsExistingCondition() {
        String id = System.getProperty("medinexus.fhir.condition-id", "200aa917-845e-754f-183b-e28858521409");
        assertThat(id.matches("[A-Za-z0-9.-]{1,64}")).as("valid logical Condition ID").isTrue();
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                        FhirConditionClient.class, ConditionMapper.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(IGenericClient.class);
                    URI expected = URI.create(client.getServerBase() + "/Condition/" + id);
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
                        public void interceptResponse(IHttpResponse response) { statuses.add(response.getStatus()); }
                    });
                    Condition condition = context.getBean(FhirConditionClient.class).readCondition(id);
                    var dto = context.getBean(ConditionMapper.class).toDto(condition);
                    assertThat(methods).containsExactly("GET");
                    assertThat(statuses).containsExactly(200);
                    assertThat(id.equals(dto.id())).as("requested logical ID preserved").isTrue();
                    String reference = condition.getSubject().getReference();
                    assertThat(reference != null && (reference.matches("Patient/[A-Za-z0-9.-]{1,64}")
                            || reference.startsWith(client.getServerBase() + "/Patient/")
                                && reference.substring((client.getServerBase() + "/Patient/").length()).matches("[A-Za-z0-9.-]{1,64}")))
                            .as("resource identifies a Patient").isTrue();
                    verifyMapping(condition, dto);
                    System.out.println("Condition read verification: conditionCount=1; getCount=" + methods.size());
                });
    }

    /** Compare without assertion messages dumping clinical contents. */
    static void verifyMapping(Condition source, ConditionDto dto) {
        assertThat("Condition".equals(source.fhirType())).isTrue();
        assertThat(dto.id() != null && dto.id().matches("[A-Za-z0-9.-]{1,64}"))
                .as("valid mapped logical ID").isTrue();
        assertThat(dto.subject() != null && Objects.equals(dto.subject().reference(), source.getSubject().getReference()))
                .as("subject reference preserved").isTrue();
        verifyConcept(source.hasCode() ? source.getCode() : null, dto.code());
        verifyConcept(source.hasClinicalStatus() ? source.getClinicalStatus() : null, dto.clinicalStatus());
        verifyConcept(source.hasVerificationStatus() ? source.getVerificationStatus() : null, dto.verificationStatus());
        verifyConcept(source.hasSeverity() ? source.getSeverity() : null, dto.severity());
        assertThat(dto.category().size()).isEqualTo(source.getCategory().size());
        for (int i = 0; i < source.getCategory().size(); i++) verifyConcept(source.getCategory().get(i), dto.category().get(i));
        verifyTemporal(source.hasOnset() ? source.getOnset() : null, dto.onset());
        verifyTemporal(source.hasAbatement() ? source.getAbatement() : null, dto.abatement());
    }

    private static void verifyConcept(CodeableConcept source, Concept dto) {
        if (source == null) { assertThat(dto == null).as("absent concept stays absent").isTrue(); return; }
        assertThat(dto != null && Objects.equals(source.getText(), dto.text())).as("concept text preserved").isTrue();
        assertThat(dto.coding().size()).isEqualTo(source.getCoding().size());
        for (int i = 0; i < source.getCoding().size(); i++) {
            var coding = source.getCoding().get(i);
            var mapped = dto.coding().get(i);
            assertThat(Objects.equals(coding.getSystem(), mapped.system()) && Objects.equals(coding.getCode(), mapped.code())
                    && Objects.equals(coding.getVersion(), mapped.version()) && Objects.equals(coding.getDisplay(), mapped.display())
                    && Objects.equals(coding.hasUserSelected() ? coding.getUserSelected() : null, mapped.userSelected()))
                    .as("coding fields preserved").isTrue();
        }
    }

    private static void verifyTemporal(Type source, ConditionDto.TemporalValue dto) {
        if (source == null) { assertThat(dto == null).as("absent temporal field stays absent").isTrue(); return; }
        String expected = switch (source.fhirType()) {
            case "dateTime" -> "DATE_TIME";
            default -> source.fhirType().toUpperCase(java.util.Locale.ROOT);
        };
        assertThat(dto != null && dto.type().name().equals(expected)).as("temporal type preserved").isTrue();
        boolean same = switch (source) {
            case DateTimeType date -> dto.data() instanceof ConditionDto.TextValue text && Objects.equals(date.getValueAsString(), text.value());
            case StringType text -> dto.data() instanceof ConditionDto.TextValue mapped && Objects.equals(text.getValue(), mapped.value());
            case Age age -> dto.data() instanceof ConditionDto.AgeValue mapped && quantityMatches(age, mapped.quantity());
            case Period period -> dto.data() instanceof ConditionDto.PeriodValue mapped
                    && Objects.equals(period.hasStart() ? period.getStartElement().getValueAsString() : null, mapped.start())
                    && Objects.equals(period.hasEnd() ? period.getEndElement().getValueAsString() : null, mapped.end());
            case Range range -> dto.data() instanceof ConditionDto.RangeValue mapped
                    && quantityMatches(range.hasLow() ? range.getLow() : null, mapped.low())
                    && quantityMatches(range.hasHigh() ? range.getHigh() : null, mapped.high());
            default -> false;
        };
        assertThat(same).as("temporal precision and details preserved").isTrue();
    }

    private static boolean quantityMatches(org.hl7.fhir.r4.model.Quantity source,
            com.medinexus.medicore.medicalrecord.dto.ObservationDto.QuantityValue dto) {
        if (source == null) return dto == null;
        return dto != null && Objects.equals(source.getValue(), dto.value()) && Objects.equals(source.getUnit(), dto.unit())
                && Objects.equals(source.getSystem(), dto.system()) && Objects.equals(source.getCode(), dto.code())
                && Objects.equals(source.hasComparator() ? source.getComparator().toCode() : null, dto.comparator());
    }
}
