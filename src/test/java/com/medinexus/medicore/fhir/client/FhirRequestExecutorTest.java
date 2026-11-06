package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import ca.uhn.fhir.parser.DataFormatException;

class FhirRequestExecutorTest {

    private final FhirRequestExecutor executor = new FhirRequestExecutor();

    @Test
    void returnsResultUnchangedAndExecutesRequestOnce() {
        Object response = new Object();
        AtomicInteger calls = new AtomicInteger();

        Object result = executor.execute("capabilities", () -> {
            calls.incrementAndGet();
            return response;
        });

        assertThat(result).isSameAs(response);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void translatesParsingFailureWithoutRetainingSensitiveDetails() {
        assertThatThrownBy(() -> executor.execute("capabilities", () -> {
            throw new DataFormatException("SENSITIVE response body");
        })).isInstanceOfSatisfying(FhirServerException.class, failure -> {
            assertThat(failure.operation()).isEqualTo("capabilities");
            assertThat(failure.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE);
            assertThat(failure.httpStatus()).isEmpty();
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).doesNotContain("SENSITIVE");
        });
    }

    @Test
    void preservesUnrelatedExceptions() {
        IllegalStateException failure = new IllegalStateException("Programming error");

        assertThatThrownBy(() -> executor.execute("capabilities", () -> {
            throw failure;
        })).isSameAs(failure);
    }
}
