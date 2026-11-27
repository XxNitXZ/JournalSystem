package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.medinexus.medicore.fhir.config.FhirClientConfig;

/**
 * Explicitly enabled check of a real TCP connect timeout: -Dmedinexus.fhir.connect-timeout-test=true
 * Connects to 192.0.2.1 port 9 (TEST-NET-1, RFC 5737, never routed on the internet) with a 500 ms connect
 * timeout. Whether the network silently drops the connection attempt (TIMEOUT) or rejects it immediately
 * (UNAVAILABLE) depends on the local network and firewall, so this test is not part of the normal build.
 * One GET only; it never contacts the KTH server.
 *
 * <p>Apache HttpClient raises ConnectTimeoutException caused by SocketTimeoutException ("connect timed out");
 * FhirServerException reports the root cause, so a connect timeout and a read timeout share the cause type
 * SocketTimeoutException and are distinguished only by when they occur.
 */
@EnabledIfSystemProperty(named = "medinexus.fhir.connect-timeout-test", matches = "true")
class FhirConnectTimeoutIT {

    @Test
    void unansweredConnectionAttemptIsATimeoutWithinTheConfiguredLimit() {
        new ApplicationContextRunner().withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                FhirPatientClient.class)
                .withPropertyValues("medinexus.fhir.base-url=http://192.0.2.1:9/fhir", "medinexus.fhir.connect-timeout=500ms",
                        "medinexus.fhir.socket-timeout=500ms", "medinexus.fhir.connection-request-timeout=500ms")
                .run(context -> {
                    long start = System.nanoTime();
                    assertThatThrownBy(() -> context.getBean(FhirPatientClient.class).readPatient("synthetic-1"))
                            .isInstanceOfSatisfying(FhirServerException.class, failure -> {
                                assertThat(failure.operation()).isEqualTo("patient-read");
                                assertThat(failure.type()).isEqualTo(FhirServerException.Type.TIMEOUT);
                                assertThat(failure.causeType()).isIn("SocketTimeoutException", "ConnectTimeoutException");
                                assertThat(failure.httpStatus()).isEmpty();
                            });
                    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
                    assertThat(elapsedMillis).isBetween(400L, 5_000L);
                    System.out.println("Connect timeout verification: elapsedMs=" + elapsedMillis);
                });
    }
}
