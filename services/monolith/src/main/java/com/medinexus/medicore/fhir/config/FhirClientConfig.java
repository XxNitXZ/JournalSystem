package com.medinexus.medicore.fhir.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.apache.http.impl.client.HttpClientBuilder;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IRestfulClientFactory;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.apache.ApacheRestfulClientFactory;

/**
 * HAPI FHIR R4 client for the external FHIR server.
 *
 * <p>Both beans are thread-safe and created once. Creating them does not
 * contact the server: server validation is disabled
 * ({@link ServerValidationModeEnum#NEVER}), so HAPI never downloads the
 * server's metadata implicitly; it is only requested explicitly.
 *
 * <p>Automatic HTTP retries are disabled: every client operation sends each
 * request at most once (see {@link NoRetryApacheRestfulClientFactory}).
 */
@Configuration
@EnableConfigurationProperties(FhirProperties.class)
public class FhirClientConfig {

    @Bean
    public FhirContext fhirContext(FhirProperties properties) {
        FhirContext context = FhirContext.forR4();
        context.setRestfulClientFactory(new NoRetryApacheRestfulClientFactory(context));
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

    /**
     * HAPI's Apache client factory with Apache HttpClient's automatic retries disabled. By default the
     * HttpClient silently resends an idempotent GET up to three times when a connection is closed without a
     * response, and logs each retry with the server address. HAPI still applies its own settings to this
     * builder: timeouts, connection pool, cookie handling and proxy configuration are unchanged.
     */
    static final class NoRetryApacheRestfulClientFactory extends ApacheRestfulClientFactory {

        NoRetryApacheRestfulClientFactory(FhirContext context) {
            super(context);
        }

        @Override
        protected HttpClientBuilder getHttpClientBuilder() {
            return super.getHttpClientBuilder().disableAutomaticRetries();
        }
    }
}
