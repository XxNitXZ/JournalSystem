package com.medinexus.medicore.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.servlet.ResultMatcher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Assertions for the common error body (NIT-328). They keep the intent of the old assertions: an error reveals
 * nothing beyond its status (previously an empty body), or exactly a known {@code code} (previously
 * {@code {"code":"…"}} compared strictly).
 */
public final class ApiErrorMatchers {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of("status", "error", "message", "code");

    private ApiErrorMatchers() {
    }

    /** An ApiError for the response status with only the fixed texts: no code, no echoed input, no details. */
    public static ResultMatcher genericError() {
        return result -> assertApiError(result.getResponse().getStatus(), result.getResponse().getContentAsString(),
                null);
    }

    /** An ApiError for the response status with exactly this code and only the fixed texts. */
    public static ResultMatcher apiError(String code) {
        return result -> assertApiError(result.getResponse().getStatus(), result.getResponse().getContentAsString(),
                code);
    }

    /** The code in an expected body written in the old form, e.g. {@code {"code":"VERSION_CONFLICT"}}. */
    public static String codeIn(String oldBody) {
        return JSON.readTree(oldBody).get("code").asString();
    }

    static void assertApiError(int status, String body, String code) {
        JsonNode json = JSON.readTree(body);
        assertThat(json.propertyNames()).as("error body fields").isSubsetOf(FIELDS);
        ApiError expected = ApiError.of(HttpStatusCode.valueOf(status), code);
        assertThat(json.get("status").asInt()).isEqualTo(status);
        assertThat(json.get("error").asString()).isEqualTo(expected.error());
        assertThat(json.get("message").asString()).as("only the fixed message").isEqualTo(expected.message());
        if (code == null) {
            assertThat(json.has("code")).as("no code").isFalse();
        } else {
            assertThat(json.get("code").asString()).isEqualTo(code);
        }
    }
}
