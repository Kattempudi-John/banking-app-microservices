package com.example.profileservice.repository;

import com.example.profileservice.model.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for {@link UserProfile}, keyed by the auth-service user id.
 *
 * <p>Deliberately empty: the profile id is the user id, so {@code findById} already answers every
 * lookup this service makes and no derived query is needed. Note that the id is assigned rather than
 * generated, which makes {@code save} an upsert against a caller-supplied key.
 */
@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
}