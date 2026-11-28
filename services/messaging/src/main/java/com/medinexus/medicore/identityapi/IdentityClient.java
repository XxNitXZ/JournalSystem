package com.medinexus.medicore.identityapi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.common.http.ServiceClients;

/**
 * Messaging's only way to learn about accounts (D4): the Identity internal API I1–I2 (docs/architecture.md §13),
 * called with a service token and the timeouts of {@link ServiceClients}.
 *
 * <p>An unknown account is part of the result (left out of the map, {@code Optional.empty()}). Every failure is
 * thrown: {@code ResourceAccessException} when Identity is unreachable or too slow, {@code RestClientResponseException}
 * when it answers with an error. {@code GlobalExceptionHandler} turns them into 503 or 502, so nothing is saved and
 * no list is shown with unchecked names.
 */
@Component
public class IdentityClient {

    /** Identity accepts at most this many ids per I1 call; longer lists are split. */
    static final int BATCH_SIZE = 200;

    /** One account as Identity's directory describes it: the public fields only. */
    public record DirectoryUser(Long id, String displayName, Role role) {

        public boolean isProfessional() {
            return role == Role.DOCTOR || role == Role.STAFF;
        }

        public boolean isPatient() {
            return role == Role.PATIENT;
        }
    }

    record DirectoryUsers(List<DirectoryUser> users) {
    }

    private final RestClient identity;

    public IdentityClient(ServiceClients clients, @Value("${medinexus.identity.url}") String identityUrl) {
        this.identity = clients.forService(identityUrl);
    }

    /** I1: the known accounts among these ids, by id. One call per {@link #BATCH_SIZE} ids; none for no ids. */
    public Map<Long, DirectoryUser> usersById(Collection<Long> ids) {
        List<Long> distinct = ids.stream().distinct().toList();
        Map<Long, DirectoryUser> found = new LinkedHashMap<>();
        for (int start = 0; start < distinct.size(); start += BATCH_SIZE) {
            String batch = distinct.subList(start, Math.min(start + BATCH_SIZE, distinct.size())).stream()
                    .map(String::valueOf).collect(Collectors.joining(","));
            for (DirectoryUser user : fetch("ids", batch)) found.put(user.id(), user);
        }
        return found;
    }

    /** I1 for one id; empty if Identity has no such account. */
    public Optional<DirectoryUser> user(Long id) {
        return Optional.ofNullable(usersById(List.of(id)).get(id));
    }

    /** I2: every account with one of these roles, in Identity's order (by name). */
    public List<DirectoryUser> usersWithRoles(Role... roles) {
        return fetch("roles", Arrays.stream(roles).map(Role::name).collect(Collectors.joining(",")));
    }

    private List<DirectoryUser> fetch(String parameter, String value) {
        DirectoryUsers result = identity.get()
                .uri(uri -> uri.path("/internal/users").queryParam(parameter, value).build())
                .retrieve().body(DirectoryUsers.class);
        return result == null || result.users() == null ? List.of() : new ArrayList<>(result.users());
    }
}
