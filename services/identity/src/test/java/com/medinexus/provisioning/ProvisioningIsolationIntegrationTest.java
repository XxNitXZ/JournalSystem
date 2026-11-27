package com.medinexus.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.medinexus.medicore.IdentityApplication;
import com.medinexus.medicore.auth.service.AccountFhirIdentityService;

/** Even valid activation settings do not turn normal startup into a provisioning run. */
@EnabledIfEnvironmentVariable(named = "IDENTITY_DB_PASSWORD", matches = ".+")
@SpringBootTest(classes = IdentityApplication.class, properties = {
        "medinexus.fhir.provisioning.enabled=true",
        "medinexus.fhir.provisioning.account-id=42",
        "medinexus.fhir.provisioning.fhir-id=synthetic-1"
})
class ProvisioningIsolationIntegrationTest {
    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private AccountFhirIdentityService service;

    @Test
    void normalStartupDoesNotDiscoverProvisioningConfigurationOrAssignAccounts() {
        assertThat(context.getBeanNamesForType(FhirIdentityProvisioning.ProvisioningConfiguration.class)).isEmpty();
        verifyNoInteractions(service);
    }
}
