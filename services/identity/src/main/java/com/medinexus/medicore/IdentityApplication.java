package com.medinexus.medicore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Identity service (US-02.01). It lives in the root package {@code com.medinexus.medicore}, like the monolith,
 * so the classes moved here in NIT-330 keep their packages, and Spring also finds the shared beans in
 * {@code medicore-common} (for example {@code GlobalExceptionHandler}).
 */
@SpringBootApplication
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
