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
}
