package com.medinexus.medicore.common.exception;

import static com.medinexus.medicore.common.exception.ApiErrorMatchers.genericError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.SocketTimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** The fallback mapping for errors no controller handles itself (NIT-328). */
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private static final String SECRET = "Tolvan Testsson 199001012385";

    /** A controller that only fails, in every way the fallback covers. */
    @RestController
    static class FailingController {
        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("Internal detail about " + SECRET);
        }

        @GetMapping("/slow-service")
        String slowService() {
            throw new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out"));
        }

        @GetMapping("/broken-service")
        String brokenService() {
            throw HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", null, null, null);
        }

        @GetMapping("/number")
        String number(@RequestParam int value) {
            return "ok";
        }

        @PostMapping("/json")
        String json(@RequestBody java.util.Map<String, String> body) {
            return "ok";
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void anUnexpectedFailureIsAGeneric500ThatLeaksNothing(CapturedOutput output) throws Exception {
        mvc.perform(get("/boom")).andExpect(status().isInternalServerError()).andExpect(genericError())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Tolvan"))));
        // The log names the type and place, never the message.
        assertThat(output.getAll()).contains("Unhandled java.lang.IllegalStateException");
        assertThat(output.getAll()).doesNotContain("Tolvan", "199001012385");
    }

    @Test
    void anotherServiceTimingOutIs503() throws Exception {
        mvc.perform(get("/slow-service")).andExpect(status().isServiceUnavailable()).andExpect(genericError());
    }

    @Test
    void anotherServiceFailingIs502() throws Exception {
        mvc.perform(get("/broken-service")).andExpect(status().isBadGateway()).andExpect(genericError());
    }

    @Test
    void malformedInputIs400() throws Exception {
        mvc.perform(get("/number").param("value", "not-a-number")).andExpect(status().isBadRequest())
                .andExpect(genericError());
        mvc.perform(get("/number")).andExpect(status().isBadRequest()).andExpect(genericError());
        mvc.perform(post("/json").contentType(MediaType.APPLICATION_JSON).content("{" + SECRET))
                .andExpect(status().isBadRequest()).andExpect(genericError());
    }

    @Test
    void wrongMethodAndContentTypeHaveTheirOwnStatus() throws Exception {
        mvc.perform(post("/number")).andExpect(status().isMethodNotAllowed()).andExpect(genericError());
        mvc.perform(post("/json").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(genericError());
    }

    @Test
    void theBodyShapeIsTheSameEverywhere() {
        ApiError error = ApiError.of(HttpStatus.CONFLICT, "EMAIL_TAKEN");
        assertThat(error.toJson()).isEqualTo("{\"status\":409,\"error\":\"Conflict\","
                + "\"message\":\"The request conflicts with the current state.\",\"code\":\"EMAIL_TAKEN\"}");
        assertThat(ApiError.of(HttpStatus.NOT_FOUND).toJson()).doesNotContain("code");
    }
}
