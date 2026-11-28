package com.medinexus.medicore.internal;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.common.exception.ApiError;

/**
 * The Identity internal API I1–I2 for Messaging (D4, docs/architecture.md §13). Only service tokens get in
 * ({@code /internal/**} in SecurityConfig). It returns the public directory fields of an account and nothing else:
 * no e-mail, personnummer, phone number or FHIR id.
 */
@RestController
@RequestMapping("/internal/users")
public class IdentityInternalController {

    /** The most ids one I1 call may ask for. A page of messages has far fewer counterparts than this. */
    static final int MAX_IDS = 200;

    /** One account as Messaging shows it. */
    public record DirectoryUser(Long id, String displayName, Role role) {

        static DirectoryUser of(User user) {
            return new DirectoryUser(user.getId(), IdentityInternalController.displayName(user), user.getRole());
        }
    }

    public record DirectoryUsers(List<DirectoryUser> users) {
    }

    private final UserRepository users;

    public IdentityInternalController(UserRepository users) {
        this.users = users;
    }

    /**
     * I1 {@code ?ids=4,7,9}: these accounts, sorted by id. Unknown ids are left out. I2 {@code ?roles=DOCTOR,STAFF}:
     * every account with one of these roles, sorted by name. Exactly one of the two parameters must be given.
     */
    @GetMapping
    public DirectoryUsers find(@RequestParam(required = false) String ids,
            @RequestParam(required = false) String roles) {
        if ((ids == null) == (roles == null)) throw new IllegalArgumentException("Give either ids or roles");
        return new DirectoryUsers(ids != null ? byIds(parseIds(ids)) : byRoles(parseRoles(roles)));
    }

    private List<DirectoryUser> byIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return users.findAllById(ids).stream().map(DirectoryUser::of)
                .sorted(Comparator.comparing(DirectoryUser::id)).toList();
    }

    /** Same order as the Lab 1 recipient list: name (case-insensitive, then exact), role, id. */
    private List<DirectoryUser> byRoles(List<Role> roles) {
        return users.findByRoleIn(roles).stream().map(DirectoryUser::of)
                .sorted(Comparator.comparing(DirectoryUser::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(DirectoryUser::displayName)
                        .thenComparing(DirectoryUser::role)
                        .thenComparing(DirectoryUser::id))
                .toList();
    }

    private static List<Long> parseIds(String value) {
        List<Long> ids = parts(value).map(Long::valueOf).distinct().toList();
        if (ids.size() > MAX_IDS || ids.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("Invalid ids");
        }
        return ids;
    }

    private static List<Role> parseRoles(String value) {
        List<Role> roles = parts(value).map(Role::valueOf).distinct().toList();
        if (roles.isEmpty()) throw new IllegalArgumentException("Invalid roles");
        return roles;
    }

    private static Stream<String> parts(String value) {
        return Arrays.stream(value.split(",", -1)).map(String::strip).filter(part -> !part.isEmpty());
    }

    /**
     * First and last name; without stored names "Doctor #4", "Staff #5" or "Patient #6" (the Lab 1 rule, moved here
     * from MessageService).
     */
    static String displayName(User user) {
        String name = Stream.of(user.getGivenName(), user.getFamilyName())
                .filter(part -> part != null && !part.isBlank()).map(String::strip)
                .collect(Collectors.joining(" "));
        if (!name.isEmpty()) return name;
        return (user.getRole() == Role.DOCTOR ? "Doctor" : user.getRole() == Role.STAFF ? "Staff" : "Patient")
                + " #" + user.getId();
    }

    /** A malformed id or role name (NumberFormatException is an IllegalArgumentException). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid() {
        return ApiError.response(HttpStatus.BAD_REQUEST);
    }
}
