package com.medinexus.medicore.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.config.CorsConfig;
import com.medinexus.medicore.config.SecurityConfig;

import tools.jackson.databind.ObjectMapper;

/** Real servlet-container session/cookie lifecycle, without a database dependency. */
@SpringBootTest(classes = AuthenticationSessionIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthenticationSessionIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({SecurityConfig.class, CorsConfig.class, AuthenticationController.class})
    static class TestApplication {
    }

    @LocalServerPort
    private int port;

    @MockitoBean
    private AuthenticationService service;

    @Test
    void httpOnlyCookieSupportsSessionAndIsInvalidAfterLogout() throws Exception {
        when(service.authenticate("user@example.com", "a secure password"))
                .thenReturn(new UserDto(42L, "user@example.com", Role.PATIENT));
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            ObjectMapper mapper = new ObjectMapper();
            HttpResponse<String> bootstrap = client.send(request("/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(bootstrap.statusCode()).isEqualTo(200);
            String setCookie = bootstrap.headers().firstValue("set-cookie").orElseThrow();
            assertThat(setCookie).contains("JSESSIONID=", "HttpOnly", "SameSite=Lax");
            var token = mapper.readTree(bootstrap.body());
            HttpResponse<String> login = client.send(request("/login")
                    .header(token.get("headerName").asString(), token.get("token").asString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"email":"user@example.com","password":"a secure password"}
                            """ )).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(login.headers().firstValue("set-cookie").orElseThrow()).contains("HttpOnly");
            String oldCookie = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                    .map(cookie -> cookie.getName() + "=" + cookie.getValue()).findFirst().orElseThrow();
            HttpResponse<String> me = client.send(request("/me").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(me.body()).get("email").asString()).isEqualTo("user@example.com");
            HttpResponse<String> refresh = client.send(request("/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
            token = mapper.readTree(refresh.body());
            HttpResponse<String> logout = client.send(request("/logout")
                    .header(token.get("headerName").asString(), token.get("token").asString())
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(logout.statusCode()).isEqualTo(204);
            try (HttpClient replayClient = HttpClient.newHttpClient()) {
                HttpResponse<String> replay = replayClient.send(request("/me").header("Cookie", oldCookie)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(replay.statusCode()).isEqualTo(401);
            }
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth" + path));
    }
}
