package com.medinexus.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.medinexus.medicore.MediNexusApplication;
import com.medinexus.medicore.auth.service.AccountFhirIdentityService;

/** Even valid activation settings do not turn normal startup into a provisioning run. */
@SpringBootTest(classes = MediNexusApplication.class, properties = {
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
