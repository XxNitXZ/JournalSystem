
package com.medinexus.medicore.auth.repository;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class UserRepositoryIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldSaveAndFindUserByEmail() {
        User user = new User();
        user.setEmail("repository-test@example.com");
        user.setPasswordHash("test-hash");
        user.setRole(Role.PATIENT);

        userRepository.save(user);

        Optional<User> result =
                userRepository.findByEmail("repository-test@example.com");

        assertTrue(result.isPresent());
        assertEquals("repository-test@example.com", result.get().getEmail());
        assertEquals(Role.PATIENT, result.get().getRole());
    }

    @Test
    void shouldCheckIfEmailExists() {
        User user = new User();
        user.setEmail("exists-test@example.com");
        user.setPasswordHash("test-hash");
        user.setRole(Role.PATIENT);

        userRepository.save(user);

        assertTrue(userRepository.existsByEmail("exists-test@example.com"));
        assertFalse(userRepository.existsByEmail("missing@example.com"));
    }

    @Test
    void shouldRejectDuplicateEmail() {
        User firstUser = new User();
        firstUser.setEmail("duplicate@example.com");
        firstUser.setPasswordHash("test-hash-1");
        firstUser.setRole(Role.PATIENT);

        userRepository.saveAndFlush(firstUser);

        User secondUser = new User();
        secondUser.setEmail("duplicate@example.com");
        secondUser.setPasswordHash("test-hash-2");
        secondUser.setRole(Role.DOCTOR);

        assertThrows(
            DataIntegrityViolationException.class,
            () -> userRepository.saveAndFlush(secondUser)
        );
    }
}
