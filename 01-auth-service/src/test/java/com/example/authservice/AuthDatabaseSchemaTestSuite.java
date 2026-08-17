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

/**
 * Persistence-layer tests for the four auth tables — {@code recognized_devices},
 * {@code two_factor_codes}, {@code refresh_tokens} and {@code blacklisted_tokens} — run against a
 * real database engine rather than mocks.
 *
 * <p><strong>Slice, and why this one.</strong> This is the only {@code @DataJpaTest} in the auth
 * service, and the contrast with {@link AuthManagementTestSuite} is the point of it existing.
 * {@code AuthManagementTestSuite} boots the full application with {@code @SpringBootTest} and
 * replaces every repository with a {@code @MockBean}, so it can prove what the services do with the
 * rows they are handed but can never prove anything about the rows themselves: a mocked
 * {@code save} accepts a duplicate {@code device_hash} happily, a mocked
 * {@code findByUserIdAndDeviceHash} returns whatever it was stubbed to return whether or not Spring
 * Data could actually derive that query, and a mocked {@code deleteByUserId} reports success without
 * a {@code DELETE} ever being issued. This suite is where those claims are actually checked. It
 * loads only the JPA layer — entities, repositories, the {@code EntityManager} and a
 * {@code DataSource}. No controllers, no services, no security filter chain, no Kafka; those beans
 * are not merely mocked here, they are never instantiated.
 *
 * <p><strong>What is real:</strong> essentially everything in the slice. The repositories are the
 * genuine Spring Data proxies, so every derived query name and {@code @Query} in them is compiled
 * and validated at context startup — a method name Spring Data cannot parse fails this class before
 * a single test runs. {@link TestEntityManager} is a thin wrapper over the real JPA
 * {@code EntityManager}, used instead of the repositories for arrange steps so that setting a row up
 * never depends on the repository method the test is about to judge.
 *
 * <p><strong>External dependency:</strong> an in-memory H2 database, and nothing else. There is no
 * Testcontainers, no Postgres and no broker. {@code @DataJpaTest} replaces the configured datasource
 * with an embedded one automatically, and {@code src/test/resources/application.properties} disables
 * Flyway, so the schema under test is the one Hibernate generates from the entity mappings. That is
 * worth being precise about: the unique index asserted below is the one declared by
 * {@code @Column(name = "device_hash", unique = true)}, so what these tests pin is that the mapping
 * says what the code assumes — the Flyway migration has to be kept in agreement with it separately.
 *
 * <p><strong>Transactional rollback.</strong> {@code @DataJpaTest} is meta-annotated
 * {@code @Transactional}, so each test method runs inside its own transaction that the TestContext
 * framework rolls back when the method ends. Nothing any test writes is ever committed, no test can
 * observe another test's rows, and no cleanup or {@code @AfterEach} is required. Two consequences
 * shape how the tests are written:
 * <ul>
 *   <li>Writes must be flushed explicitly. Inside an open transaction Hibernate is free to defer the
 *       {@code INSERT} until commit — which never comes — so a constraint would never be exercised.
 *       {@code persistAndFlush} and {@code flush()} are what push the SQL to H2 and make the
 *       database's answer observable.</li>
 *   <li>Reads must escape the first-level cache. The persistence context returns the same instance
 *       it already has in its identity map, so a read after a bulk {@code UPDATE}/{@code DELETE} —
 *       which JPQL executes straight against the database, bypassing the session — would return the
 *       stale in-memory object. {@code entityManager.clear()} detaches everything and forces the
 *       next read to hit the database.</li>
 * </ul>
 *
 * <p><strong>Fixture state:</strong> none shared. There is no {@code @BeforeEach} or
 * {@code @BeforeAll}; every test arranges its own rows and, although rollback already isolates them,
 * each uses its own user-id band (100, 200, 300, 310, 320, 330, 400) so that a failure message names
 * one test unambiguously. No test depends on another's state or on execution order.
 *
 * <p><strong>The two-factor TTL group.</strong> Three of these tests round-trip the 2FA code
 * lifetime through the database rather than asserting on an in-memory object, because the bug they
 * guard is a mismatch between two deadlines: the row's {@code expires_at} and the countdown the
 * login response tells the browser to display. They pin that {@code expires_at} is derived from the
 * TTL passed in rather than a literal, that the two-argument constructor lands on the same 180-second
 * default the configuration property falls back to, and that {@code created_at} is genuinely
 * persisted and readable — it is what the resend cooldown is measured from, and until these tests it
 * was only ever written, never read back.
 */
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

    /**
     * Two exception types are accepted because the failure surfaces at different layers depending on
     * how the driver reports the violation: Spring translates it to
     * {@link DataIntegrityViolationException}, but when Hibernate detects it first the raw
     * {@code org.hibernate.exception.ConstraintViolationException} escapes untranslated. Either one
     * proves the database refused the row, which is the claim under test; pinning a single type
     * would make this test a driver-behaviour assertion instead.
     */
    @Test
    @DisplayName("Table 1: Enforce UNIQUE constraint on device_hash - [MEANT TO PASS]")
    void persistRecognizedDevice_deviceHashAlreadyRegisteredToAnotherUser_isRejectedByUniqueConstraint() {
        RecognizedDevice device1 = new RecognizedDevice(100L, "duplicate-hash-123");
        entityManager.persistAndFlush(device1);

        RecognizedDevice device2 = new RecognizedDevice(101L, "duplicate-hash-123");

        assertThatThrownBy(() -> {
            entityManager.persistAndFlush(device2);
        }).isInstanceOfAny(
            DataIntegrityViolationException.class,
            org.hibernate.exception.ConstraintViolationException.class
        );
    }

    @Test
    @DisplayName("Table 1: Query findByUserIdAndDeviceHash retrieves correct record - [MEANT TO PASS]")
    void findByUserIdAndDeviceHash_rowMatchingBothArguments_returnsThatDevice() {
        RecognizedDevice device = new RecognizedDevice(200L, "unique-device-hash-999");
        entityManager.persistAndFlush(device);

        Optional<RecognizedDevice> found = deviceRepository.findByUserIdAndDeviceHash(200L, "unique-device-hash-999");

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(200L);
    }

    /**
     * {@code deleteByUserId} is a custom modifying query rather than a derived {@code delete}
     * helper, so this checks the row is genuinely gone from the table — not merely detached, and not
     * flagged in some column the reissue path would still trip over.
     */
    @Test
    @DisplayName("Table 2: deleteByUserId purges active 2FA codes - [MEANT TO PASS]")
    void deleteByUserId_userHasAnActiveCode_removesTheRowEntirely() {
        TwoFactorCode code = new TwoFactorCode(300L, "hashed-2fa-code");
        entityManager.persistAndFlush(code);

        twoFactorCodeRepository.deleteByUserId(300L);
        entityManager.flush();

        Optional<TwoFactorCode> found = twoFactorCodeRepository.findByUserId(300L);
        assertThat(found).isEmpty();
    }

    /**
     * The row's lifetime and the countdown the login response reports are now one configured value,
     * which is why the constructor takes a TTL instead of hardcoding one. This pins that
     * {@code expires_at} really is derived from what was passed in: a stale literal in the entity
     * would put the on-screen countdown and the database on two different deadlines.
     */
    @Test
    @DisplayName("Table 2: TwoFactorCode expires_at is derived from the TTL it was given - [MEANT TO PASS]")
    void persistTwoFactorCode_explicitTtlSupplied_storesExpiresAtThatManySecondsAfterCreatedAt() {
        LocalDateTime before = LocalDateTime.now();
        TwoFactorCode code = new TwoFactorCode(310L, "hashed-ttl-code", 180);
        entityManager.persistAndFlush(code);
        entityManager.clear(); // read the persisted values back, not the in-memory object

        Optional<TwoFactorCode> stored = twoFactorCodeRepository.findByUserId(310L);

        assertThat(stored).isPresent();
        assertThat(stored.get().getCreatedAt()).isNotNull();
        assertThat(stored.get().getExpiresAt()).isNotNull();
        assertThat(Duration.between(stored.get().getCreatedAt(), stored.get().getExpiresAt()).getSeconds())
                .isEqualTo(180);
        // and the code is live right now rather than born expired
        assertThat(stored.get().isExpired()).isFalse();
        // one second of slack absorbs the clock ticking between the reading above and the constructor
        assertThat(stored.get().getCreatedAt()).isAfterOrEqualTo(before.minusSeconds(1));
    }

    /**
     * The TTL-less constructor still exists for callers that have no configured value to hand, and it
     * has to land on the same 180 seconds the property defaults to. Two different defaults would mean
     * a code whose database deadline disagrees with the countdown the user is watching.
     */
    @Test
    @DisplayName("Table 2: TwoFactorCode two-arg constructor falls back to the shared 180s default - [MEANT TO PASS]")
    void persistTwoFactorCode_twoArgConstructorWithNoTtl_appliesTheSharedDefaultTtl() {
        TwoFactorCode code = new TwoFactorCode(320L, "hashed-default-ttl-code");
        entityManager.persistAndFlush(code);
        entityManager.clear();

        Optional<TwoFactorCode> stored = twoFactorCodeRepository.findByUserId(320L);

        assertThat(stored).isPresent();
        assertThat(Duration.between(stored.get().getCreatedAt(), stored.get().getExpiresAt()).getSeconds())
                .isEqualTo(TwoFactorCode.DEFAULT_TTL_SECONDS);
    }

    /**
     * {@code created_at} is what the 30-second resend cooldown is measured from, and it was only ever
     * written, never read — so nothing before this would have caught it coming back null or unset
     * after a round trip through the database.
     */
    @Test
    @DisplayName("Table 2: created_at is persisted and readable for the resend cooldown - [MEANT TO PASS]")
    void findByUserId_codeMintedMomentsAgo_returnsAPersistedCreatedAtInsideTheResendCooldown() {
        TwoFactorCode code = new TwoFactorCode(330L, "hashed-cooldown-code", 180);
        entityManager.persistAndFlush(code);
        entityManager.clear();

        Optional<TwoFactorCode> stored = twoFactorCodeRepository.findByUserId(330L);

        assertThat(stored).isPresent();
        // 30 is the resend cooldown window: a code minted a moment ago must still read as inside it
        assertThat(Duration.between(stored.get().getCreatedAt(), LocalDateTime.now()).getSeconds())
                .isLessThan(30);
    }

    /**
     * Two tokens are persisted so that {@code revokeAllUserTokens} is exercised as a genuine bulk
     * update over a user's rows rather than a single-row write, though only the first is read back
     * and asserted on. The {@code clear()} is required, not tidiness: the JPQL bulk update runs
     * straight against the database, so without detaching the persistence context the read would
     * return the stale unrevoked instance still sitting in the identity map.
     */
    @Test
    @DisplayName("Table 3: revokeAllUserTokens flips the revoked flag on a user's active token - [MEANT TO PASS]")
    void revokeAllUserTokens_userWithMultipleActiveTokens_marksTheTokenRevokedAndNoLongerValid() {
        RefreshToken token1 = new RefreshToken(400L, "hash-token-1");
        RefreshToken token2 = new RefreshToken(400L, "hash-token-2");
        entityManager.persist(token1);
        entityManager.persist(token2);
        entityManager.flush();

        refreshTokenRepository.revokeAllUserTokens(400L);
        entityManager.clear();

        Optional<RefreshToken> updatedToken1 = refreshTokenRepository.findByTokenHash("hash-token-1");
        assertThat(updatedToken1).isPresent();
        assertThat(updatedToken1.get().getRevoked()).isTrue();
        assertThat(updatedToken1.get().isValid()).isFalse();
    }

    /**
     * The cutoff is the whole point: the scheduled cleanup must delete by expiry rather than empty
     * the table. One JTI is stamped ten minutes in the past and one ten minutes in the future so that
     * a query missing its {@code WHERE} clause takes the still-blacklisted token with it and fails
     * the second assertion — a token dropped from the blacklist early is a revoked JWT that starts
     * being accepted again.
     */
    @Test
    @DisplayName("Table 4: deleteAllExpiredTokensSince purges expired JWT JTIs and leaves live ones in place - [MEANT TO PASS]")
    void deleteAllExpiredTokensSince_tableHoldsOneExpiredAndOneLiveJti_removesOnlyTheExpiredOne() {
        String expiredJti = "a1b2c3d4-e5f6-7a8b-9c0d-expired1111";
        String activeJti = "a1b2c3d4-e5f6-7a8b-9c0d-active22222";

        BlacklistedToken expiredToken = new BlacklistedToken(expiredJti, LocalDateTime.now().minusMinutes(10));
        BlacklistedToken activeToken = new BlacklistedToken(activeJti, LocalDateTime.now().plusMinutes(10));

        entityManager.persist(expiredToken);
        entityManager.persist(activeToken);
        entityManager.flush();

        blacklistedTokenRepository.deleteAllExpiredTokensSince(LocalDateTime.now());
        entityManager.flush();

        assertThat(blacklistedTokenRepository.existsById(expiredJti)).isFalse();
        assertThat(blacklistedTokenRepository.existsById(activeJti)).isTrue();
    }
}
