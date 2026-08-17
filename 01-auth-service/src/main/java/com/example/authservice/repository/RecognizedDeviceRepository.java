package com.example.authservice.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.authservice.model.RecognizedDevice;

/**
 * Looks up devices a user has already been challenged on.
 */
@Repository
public interface RecognizedDeviceRepository extends JpaRepository<RecognizedDevice, Long> {

    /**
     * Finds the device entry belonging to one specific user.
     *
     * <p>Both conditions are required, not alternatives: a device hash that exists but under a
     * different user returns empty, which is what stops a stolen device cookie from skipping 2FA on
     * someone else's account.
     *
     * @param userId the account the device must belong to
     * @param deviceHash the hashed cookie value, never the raw cookie; an unhashed value simply
     *     fails to match rather than erroring
     * @return empty when this user has not been seen on this device, which callers should treat as
     *     "challenge required"
     */
    Optional<RecognizedDevice> findByUserIdAndDeviceHash(Long userId, String deviceHash);
    
}