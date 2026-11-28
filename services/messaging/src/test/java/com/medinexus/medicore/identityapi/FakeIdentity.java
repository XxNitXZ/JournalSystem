package com.medinexus.medicore.identityapi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.identityapi.IdentityClient.DirectoryUser;
import com.medinexus.medicore.security.jwt.TestTokens;
import com.medinexus.medicore.security.jwt.UserClaims;

/**
 * A stand-in for Identity in Messaging's tests: a mocked {@link IdentityClient} that answers I1/I2 from an in-memory
 * directory, with Identity's display-name rule ("Maja Synthetic", else "Patient #id"). Account ids start at a random
 * high number, so they never meet real account ids in a shared test database.
 */
public final class FakeIdentity {

    private static final AtomicLong NEXT_ID =
            new AtomicLong(9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L));

    private final Map<Long, DirectoryUser> accounts = new ConcurrentHashMap<>();

    private FakeIdentity() {
    }

    /** Makes the mocked client answer from a new, empty directory. */
    public static FakeIdentity on(IdentityClient client) {
        FakeIdentity fake = new FakeIdentity();
        when(client.usersById(any())).thenAnswer(call -> {
            Map<Long, DirectoryUser> found = new LinkedHashMap<>();
            for (Long id : call.<Collection<Long>>getArgument(0)) {
                if (fake.accounts.containsKey(id)) found.put(id, fake.accounts.get(id));
            }
            return found;
        });
        when(client.user(anyLong())).thenAnswer(call -> Optional.ofNullable(fake.accounts.get(call.<Long>getArgument(0))));
        when(client.usersWithRoles(any(Role[].class))).thenAnswer(call -> {
            List<Role> roles = Arrays.asList((Role[]) call.getRawArguments()[0]);
            return fake.accounts.values().stream().filter(user -> roles.contains(user.role())).toList();
        });
        return fake;
    }

    /** A new account; with a first name its display name is "<given> Synthetic", without one "Doctor #id" etc. */
    public Long account(Role role, String given) {
        long id = NEXT_ID.incrementAndGet();
        String fallback = (role == Role.DOCTOR ? "Doctor" : role == Role.STAFF ? "Staff" : "Patient") + " #" + id;
        accounts.put(id, new DirectoryUser(id, given == null ? fallback : given + " Synthetic", role));
        return id;
    }

    /** An id Identity has never heard of. */
    public static Long unknownId() {
        return NEXT_ID.incrementAndGet();
    }

    /** Signed in as this account with this role in the token, as Identity's login would set it. */
    public static RequestPostProcessor as(Long id, Role role) {
        return request -> {
            request.setCookies(TestTokens.cookieFor(new UserClaims(id, "synthetic@example.test", role, null, null,
                    null, null)));
            return request;
        };
    }
}
