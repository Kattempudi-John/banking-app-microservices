package com.example.authservice;

import com.example.authservice.model.BlacklistedToken;
import com.example.authservice.model.RecognizedDevice;
import com.example.authservice.model.RefreshToken;
import com.example.authservice.model.TwoFactorCode;
import com.example.authservice.repository.BlacklistedTokenRepository;
import com.example.authservice.repository.RecognizedDeviceRepository;
import com.example.authservice.repository.RefreshTokenRepository;
import com.example.authservice.repository.TwoFactorCodeRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@DisplayName("Database Schema & JPA Repository Test Suite")
class AuthDatabaseSchemaTestSuite {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private RecognizedDeviceRepository deviceRepository;

    @Autowired
    private TwoFactorCodeRepository twoFactorCodeRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private BlacklistedTokenRepository blacklistedTokenRepository;

    // this one is testing the unique constraint on the device_hash column
    // first I save a device with a hash so there is already a row sitting in the table
    // then I make a second device object using that exact same hash string
    // when I try to save that second one the database should reject it
    // spring wraps the raw sql constraint error so I check for either the generic
    // data integrity exception or the hibernate specific constraint one, since it
    // can come back as either depending on the driver
    @Test
    @DisplayName("Table 1: Enforce UNIQUE constraint on device_hash - [MEANT TO PASS]")
    void testRecognizedDevice_UniqueHashConstraint() {
        // Given: An existing device registered with a specific hash
        RecognizedDevice device1 = new RecognizedDevice(100L, "duplicate-hash-123");
        entityManager.persistAndFlush(device1);

        // When: Attempting to insert a second record with the identical device_hash
        RecognizedDevice device2 = new RecognizedDevice(101L, "duplicate-hash-123");

        // Then: Database throws exception enforcing UNIQUE constraint
        assertThatThrownBy(() -> {
            entityManager.persistAndFlush(device2);
        }).isInstanceOfAny(
            DataIntegrityViolationException.class, 
            org.hibernate.exception.ConstraintViolationException.class
        );
    }

    // this one checks the custom repository method findbyuseridanddevicehash
    // I save one device tied to a user id and a hash value
    // then call the repository method with that same user id and hash
    // it should find the row and come back wrapped in a non empty optional
    // and the user id on the entity that comes back should match what I saved
    @Test
    @DisplayName("Table 1: Query findByUserIdAndDeviceHash retrieves correct record - [MEANT TO PASS]")
    void testRecognizedDevice_MagicMethodQuery() {
        // Given: Stored device hash
        RecognizedDevice device = new RecognizedDevice(200L, "unique-device-hash-999");
        entityManager.persistAndFlush(device);

        // When: Executing repository query
        Optional<RecognizedDevice> found = deviceRepository.findByUserIdAndDeviceHash(200L, "unique-device-hash-999");

        // Then: Matching record is returned
        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(200L);
    }

    // testing the deletebyuserid query on the two factor code table
    // I persist one active 2fa code for a user first
    // then call deletebyuserid which is a custom modifying query, not a default jpa method
    // after flushing I look the code up again by that same user id
    // it should come back empty since the row was actually removed from the db and
    // not just marked as something else
    @Test
    @DisplayName("Table 2: deleteByUserId purges active 2FA codes - [MEANT TO PASS]")
    void testTwoFactorCode_DeleteByUserId() {
        // Given: Active 2FA code in DB
        TwoFactorCode code = new TwoFactorCode(300L, "hashed-2fa-code");
        entityManager.persistAndFlush(code);

        // When: Invoking deleteByUserId custom modifying query
        twoFactorCodeRepository.deleteByUserId(300L);
        entityManager.flush();

        // Then: Code is permanently deleted
        Optional<TwoFactorCode> found = twoFactorCodeRepository.findByUserId(300L);
        assertThat(found).isEmpty();
    }

    // the lifetime the row expires on and the number the login response counts down from are now the
    // same configured value, so the constructor takes it rather than hardcoding one - this pins that
    // expires_at is actually derived from what was passed in, since a stale literal in here would put
    // the on-screen countdown and the database on two different deadlines
    @Test
    @DisplayName("Table 2: TwoFactorCode expires_at is derived from the TTL it was given - [MEANT TO PASS]")
    void testTwoFactorCode_TtlDrivesExpiresAt() {
        // Given: A code minted with an explicit 180 second lifetime
        LocalDateTime before = LocalDateTime.now();
        TwoFactorCode code = new TwoFactorCode(310L, "hashed-ttl-code", 180);
        entityManager.persistAndFlush(code);
        entityManager.clear(); // read the persisted values back, not the in-memory object

        // When: Reading the row back out of the database
        Optional<TwoFactorCode> stored = twoFactorCodeRepository.findByUserId(310L);

        // Then: Both timestamp columns survived the round trip and sit 180 seconds apart
        assertThat(stored).isPresent();
        assertThat(stored.get().getCreatedAt()).isNotNull();
        assertThat(stored.get().getExpiresAt()).isNotNull();
        assertThat(Duration.between(stored.get().getCreatedAt(), stored.get().getExpiresAt()).getSeconds())
                .isEqualTo(180);
        // and the code is live right now rather than born expired
        assertThat(stored.get().isExpired()).isFalse();
        assertThat(stored.get().getCreatedAt()).isAfterOrEqualTo(before.minusSeconds(1));
    }

    // the no-argument-TTL constructor still exists for callers that don't care, and it has to land
    // on the same 180 the property defaults to - two different defaults would mean a code whose
    // database deadline disagrees with the countdown the user is watching
    @Test
    @DisplayName("Table 2: TwoFactorCode two-arg constructor falls back to the shared 180s default - [MEANT TO PASS]")
    void testTwoFactorCode_DefaultTtlMatchesConfiguredFallback() {
        TwoFactorCode code = new TwoFactorCode(320L, "hashed-default-ttl-code");
        entityManager.persistAndFlush(code);
        entityManager.clear();

        Optional<TwoFactorCode> stored = twoFactorCodeRepository.findByUserId(320L);

        assertThat(stored).isPresent();
        assertThat(Duration.between(stored.get().getCreatedAt(), stored.get().getExpiresAt()).getSeconds())
                .isEqualTo(TwoFactorCode.DEFAULT_TTL_SECONDS);
    }

    // created_at is what the 30 second resend cooldown is measured from - it was only ever written
    // before, never read, so nothing until now would have caught it coming back null or unset
    @Test
    @DisplayName("Table 2: created_at is persisted and readable for the resend cooldown - [MEANT TO PASS]")
    void testTwoFactorCode_CreatedAtIsQueryable() {
        TwoFactorCode code = new TwoFactorCode(330L, "hashed-cooldown-code", 180);
        entityManager.persistAndFlush(code);
        entityManager.clear();

        Optional<TwoFactorCode> stored = twoFactorCodeRepository.findByUserId(330L);

        assertThat(stored).isPresent();
        // a code minted moments ago is still well inside the cooldown window
        assertThat(Duration.between(stored.get().getCreatedAt(), LocalDateTime.now()).getSeconds())
                .isLessThan(30);
    }

    // this one is for the bulk revoke query on refresh tokens
    // I create two refresh tokens for the same user and persist both of them
    // then call revokealluserstokens which should flip the revoked flag on both rows at once
    // I clear the entity manager after that so I am not reading a cached copy out of the l1 cache
    // then pull one of the tokens back up by its hash and confirm revoked is true
    // and that isvalid() also reports false, since a revoked token should never read as valid
    @Test
    @DisplayName("Table 3: revokeAllUserTokens flips revoked flag for active tokens - [MEANT TO PASS]")
    void testRefreshToken_RevokeAllUserTokens() {
        // Given: Two active refresh tokens for user 400L
        RefreshToken token1 = new RefreshToken(400L, "hash-token-1");
        RefreshToken token2 = new RefreshToken(400L, "hash-token-2");
        entityManager.persist(token1);
        entityManager.persist(token2);
        entityManager.flush();

        // When: Executing custom JPQL bulk update query
        refreshTokenRepository.revokeAllUserTokens(400L);
        entityManager.clear(); // Clear L1 cache to read updated DB state

        // Then: Both tokens are flagged as revoked = true
        Optional<RefreshToken> updatedToken1 = refreshTokenRepository.findByTokenHash("hash-token-1");
        assertThat(updatedToken1).isPresent();
        assertThat(updatedToken1.get().getRevoked()).isTrue();
        assertThat(updatedToken1.get().isValid()).isFalse();
    }

    // this test is for the cron style cleanup query that purges expired blacklist entries
    // I make one token that already expired ten minutes ago and one that is still good for ten more minutes
    // both get persisted so the table has one of each kind sitting in it
    // then I call deleteallexpiredtokenssince with the current time, which should only touch the expired one
    // after that I check that the expired jti no longer exists but the still active one is untouched
    @Test
    @DisplayName("Table 4: deleteAllExpiredTokensSince purges naturally expired JWT JTIs - [MEANT TO PASS]")
    void testBlacklistedToken_PurgeExpired() {
        // Given: One expired blacklisted JTI and one active blacklisted JTI
        String expiredJti = "a1b2c3d4-e5f6-7a8b-9c0d-expired1111";
        String activeJti = "a1b2c3d4-e5f6-7a8b-9c0d-active22222";

        BlacklistedToken expiredToken = new BlacklistedToken(expiredJti, LocalDateTime.now().minusMinutes(10));
        BlacklistedToken activeToken = new BlacklistedToken(activeJti, LocalDateTime.now().plusMinutes(10));

        entityManager.persist(expiredToken);
        entityManager.persist(activeToken);
        entityManager.flush();

        // When: Cron maintenance query executes
        blacklistedTokenRepository.deleteAllExpiredTokensSince(LocalDateTime.now());
        entityManager.flush();

        // Then: Expired JTI is removed, while active blacklisted JTI remains
        assertThat(blacklistedTokenRepository.existsById(expiredJti)).isFalse();
        assertThat(blacklistedTokenRepository.existsById(activeJti)).isTrue();
    }
}