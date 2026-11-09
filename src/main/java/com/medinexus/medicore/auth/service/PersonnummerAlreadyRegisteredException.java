package com.medinexus.medicore.auth.service;

/** Another account already has this personnummer. Carries no personnummer. */
public class PersonnummerAlreadyRegisteredException extends RuntimeException {

    public PersonnummerAlreadyRegisteredException() {
        super("An account with this personnummer already exists");
    }

    public PersonnummerAlreadyRegisteredException(Throwable cause) {
        super("An account with this personnummer already exists", cause);
    }
}
