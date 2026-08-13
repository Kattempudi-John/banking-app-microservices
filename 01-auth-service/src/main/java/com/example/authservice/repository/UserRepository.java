package com.example.authservice.repository;

import com.example.authservice.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    // The email column is unique, so registration checks this up front to answer with a clear
    // "already registered" instead of letting the insert fail on a constraint violation.
    boolean existsByEmail(String email);
    // Same up-front check for the phone column. Callers must pass an already-normalized E.164
    // number - "571-285-6947" and "+15712856947" are the same phone but not the same string, so
    // comparing raw input here would let the duplicate straight through.
    boolean existsByPhoneNumber(String phoneNumber);
    // Returns the owner rather than a yes/no, because an update needs to know *who* holds the
    // number, not just that somebody does. A user re-submitting their own unchanged number is not
    // a conflict, and existsByPhoneNumber alone cannot tell that apart from a real collision - it
    // would reject the identity form every time it was saved without touching the phone field.
    Optional<User> findByPhoneNumber(String phoneNumber);
}
