package com.example.authservice.repository;

import com.example.authservice.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Loads accounts and answers the uniqueness questions registration and profile updates ask.
 *
 * <p>The {@code exists*} methods exist to turn a database constraint into a readable error: each
 * backs a column that is unique, so the up-front check lets the caller answer "already registered"
 * instead of surfacing a constraint violation from a failed insert. They are checks, not locks —
 * two concurrent registrations can both pass and one will still fail on insert, which the unique
 * constraint is there to catch.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Finds an account by its login name.
     *
     * @param username matched exactly, including case
     * @return empty for an unknown user; authentication treats this the same as a wrong password so
     *     the caller cannot probe which usernames exist
     */
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * Reports whether any account already holds this phone number.
     *
     * @param phoneNumber must already be normalized to E.164; {@code "571-285-6947"} and
     *     {@code "+15712856947"} are the same phone but not the same string, so passing raw input
     *     reports no duplicate and lets the collision through
     * @return {@code true} if the number is taken by any account, including the caller's own
     */
    boolean existsByPhoneNumber(String phoneNumber);

    /**
     * Finds the account that currently holds a phone number.
     *
     * <p>Returns the owner rather than a yes/no because an update needs to know <em>who</em> holds
     * the number. A user re-submitting their own unchanged number is not a conflict, and
     * {@link #existsByPhoneNumber} cannot tell that apart from a real collision — using it on an
     * update path rejects the identity form every time it is saved without the phone field having
     * changed.
     *
     * @param phoneNumber must already be normalized to E.164, for the same reason as above
     * @return empty when the number is unclaimed
     */
    Optional<User> findByPhoneNumber(String phoneNumber);
}
