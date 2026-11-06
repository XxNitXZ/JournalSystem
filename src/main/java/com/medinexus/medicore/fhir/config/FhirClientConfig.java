package com.medinexus.medicore.fhir.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IRestfulClientFactory;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;

/**
 * HAPI FHIR R4 client for the external FHIR server.
 *
 * <p>Both beans are thread-safe and created once. Creating them does not
 * contact the server: server validation is disabled
 * ({@link ServerValidationModeEnum#NEVER}), so HAPI never downloads the
 * server's metadata implicitly; it is only requested explicitly.
 */
@Configuration
@EnableConfigurationProperties(FhirProperties.class)
public class FhirClientConfig {

    @Bean
    public FhirContext fhirContext(FhirProperties properties) {
        FhirContext context = FhirContext.forR4();
        IRestfulClientFactory clientFactory = context.getRestfulClientFactory();
        clientFactory.setServerValidationMode(ServerValidationModeEnum.NEVER);
        clientFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        clientFactory.setSocketTimeout((int) properties.socketTimeout().toMillis());
        clientFactory.setConnectionRequestTimeout((int) properties.connectionRequestTimeout().toMillis());
        return context;
    }

    @Bean
    public IGenericClient fhirClient(FhirContext fhirContext, FhirProperties properties) {
        return fhirContext.newRestfulGenericClient(properties.baseUrl());
    }
}
