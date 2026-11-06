package com.medinexus.medicore.auth.service;

/** Association failure containing no account identifiers or underlying database details. */
public class AccountFhirIdentityException extends RuntimeException {
    public AccountFhirIdentityException(String message) {
        super(message);
    }
}
