package com.medinexus.medicore.auth;

import org.springframework.test.util.ReflectionTestUtils;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;

/** Stored accounts for tests, as the repository would return them. */
public final class TestAccounts {

    private TestAccounts() {
    }

    public static User account(Long id, String email, Role role, String givenName, String familyName) {
        User account = new User();
        ReflectionTestUtils.setField(account, "id", id);
        account.setEmail(email);
        account.setRole(role);
        account.setGivenName(givenName);
        account.setFamilyName(familyName);
        account.setPasswordHash("{noop}unused");
        return account;
    }
}
