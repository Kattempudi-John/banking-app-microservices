package com.example.notificationservice;

import com.example.notificationservice.dto.NotificationFilter;
import com.example.notificationservice.model.NotificationChannel;
import com.example.notificationservice.model.NotificationRecord;
import com.example.notificationservice.model.NotificationStatus;
import com.example.notificationservice.model.NotificationType;
import com.example.notificationservice.repository.NotificationRecordRepository;
import com.example.notificationservice.repository.NotificationSpecifications;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the filtering half of {@code GET /api/v1/notifications} — {@code
 * NotificationSpecifications.forUser} composed into a real
 * {@code JpaSpecificationExecutor#findAll} query.
 *
 * <p>{@code NotificationPersistenceTestSuite} already covers the controller end of the same feature
 * (parameter binding, response shape, the 400 for an unparseable value) with a mocked repository.
 * What cannot be proven there is whether the predicates actually compose: a specification that
 * silently ignored a filter, or one that dropped the user scoping, satisfies a mock perfectly. So
 * this suite mocks nothing — the repository, the entity manager, the SQL and the database are all
 * real, and the only thing under test is the query the specification builds.
 *
 * <h2>Fixture state</h2>
 *
 * <p>{@code @BeforeEach} reseeds six rows per test: four owned by {@code OWNER}, spanning three
 * months, two types, both channels and both statuses, so every filter below has something it must
 * include <em>and</em> something it must exclude; plus two rows under {@code OTHER_USER} that
 * duplicate shapes the owner also has, so a broken user scoping cannot hide. Nothing is shared
 * across tests and there is no {@code @BeforeAll} — each test starts from the same six rows.
 *
 * <h2>Test configuration</h2>
 *
 * <p>{@code @DataJpaTest} rather than a full {@code @SpringBootTest}: no web layer, no security and
 * no Kafka listener participates in building a {@code Specification}, and the persistence slice
 * starts in a fraction of the time.
 *
 * <p>{@code @AutoConfigureTestDatabase(replace = NONE)} is the single most important line in this
 * file. It disables Boot's default behaviour of swapping the datasource for an embedded database,
 * so the suite runs against the same local Docker Postgres every other suite boots against. That is
 * deliberate, not an oversight: {@code type}, {@code channel} and {@code status} are real PostgreSQL
 * enum columns (declared by migration {@code V1}) bound as Hibernate {@code NAMED_ENUM}, and enum
 * binding is precisely the thing that breaks in a way H2 could never reproduce — under H2 these
 * columns degrade to plain strings and every assertion here would pass while production failed.
 *
 * <p>{@code @DataJpaTest} is transactional, so each test runs in its own transaction that is rolled
 * back on completion. The seeded rows never survive a test, which is what makes the exact-count
 * assertions ({@code hasSize(1)}, {@code isEqualTo(4)}) safe to write against a shared database.
 *
 * <p>{@code spring.datasource.hikari.maximum-pool-size=2} is capped for the same reason
 * {@code InternalTokenSecurityTestSuite} caps it: each cached Spring context parks a full pool of
 * idle connections against the shared local Postgres for the rest of the run — Hikari's
 * {@code minimumIdle} defaults to {@code maximumPoolSize}, i.e. ten connections per context — and
 * going past {@code max_connections} shows up as some unrelated suite failing with "sorry, too many
 * clients already".
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2"
})
class NotificationFeedFilterTestSuite {

    // Two ids well clear of anything a local run would have created, so the assertions below can be
    // about counts rather than about "counts, plus whatever was already in this shared database".
    private static final long OWNER = 900_001L;
    private static final long OTHER_USER = 900_002L;

    private static final LocalDateTime JANUARY = LocalDateTime.of(2024, 1, 15, 9, 0);
    private static final LocalDateTime FEBRUARY = LocalDateTime.of(2024, 2, 15, 9, 0);
    private static final LocalDateTime MARCH = LocalDateTime.of(2024, 3, 15, 9, 0);

    private static final Pageable FIRST_PAGE =
            PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"));

    @Autowired
    private NotificationRecordRepository notificationRecordRepository;

    @BeforeEach
    void seedRecords() {
        save(OWNER, NotificationType.SMS_2FA, NotificationChannel.SMS, NotificationStatus.SENT, JANUARY);
        save(OWNER, NotificationType.TRANSACTION_ALERT, NotificationChannel.EMAIL, NotificationStatus.SENT, FEBRUARY);
        save(OWNER, NotificationType.TRANSACTION_ALERT, NotificationChannel.EMAIL, NotificationStatus.FAILED, MARCH);
        save(OWNER, NotificationType.PROFILE_SECURITY, NotificationChannel.EMAIL, NotificationStatus.SENT, MARCH);

        // The same shapes under a different owner. Every assertion below would still pass with a
        // broken user scoping if these did not exist.
        save(OTHER_USER, NotificationType.SMS_2FA, NotificationChannel.SMS, NotificationStatus.SENT, JANUARY);
        save(OTHER_USER, NotificationType.TRANSACTION_ALERT, NotificationChannel.EMAIL, NotificationStatus.FAILED, MARCH);
    }

    @Test
    @DisplayName("No filters returns the caller's whole feed, exactly as before - [MEANT TO PASS]")
    void forUser_noFiltersSupplied_returnsAllFourOfTheCallersRecords() {
        Page<NotificationRecord> page = query(NotificationFilter.none());

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).allMatch(record -> record.getUserId() == OWNER);
    }

    @Test
    @DisplayName("The type filter alone narrows to that type - [MEANT TO PASS]")
    void forUser_typeFilterOnly_returnsOnlyRecordsOfThatType() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                NotificationType.TRANSACTION_ALERT, null, null, null, null));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).allMatch(record -> record.getType() == NotificationType.TRANSACTION_ALERT);
    }

    @Test
    @DisplayName("The channel filter alone narrows to that channel - [MEANT TO PASS]")
    void forUser_channelFilterOnly_returnsOnlyRecordsOnThatChannel() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                null, NotificationChannel.SMS, null, null, null));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getChannel()).isEqualTo(NotificationChannel.SMS);
    }

    @Test
    @DisplayName("The status filter alone narrows to that status - [MEANT TO PASS]")
    void forUser_statusFilterOnly_returnsOnlyRecordsWithThatStatus() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                null, null, NotificationStatus.FAILED, null, null));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    /**
     * The bound is the exact timestamp of a seeded row rather than a day either side of it: an
     * exclusive lower bound would drop that boundary record, and the count of three is what notices.
     */
    @Test
    @DisplayName("The from filter alone is an inclusive lower bound - [MEANT TO PASS]")
    void forUser_fromFilterOnly_includesTheRecordExactlyOnTheLowerBound() {
        Page<NotificationRecord> page = query(new NotificationFilter(null, null, null, FEBRUARY, null));

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getContent()).allMatch(record -> !record.getCreatedAt().isBefore(FEBRUARY));
    }

    /**
     * The mirror of the lower-bound case, and the same reasoning: the bound sits exactly on a seeded
     * row, so an exclusive upper bound would return one record instead of two.
     */
    @Test
    @DisplayName("The to filter alone is an inclusive upper bound - [MEANT TO PASS]")
    void forUser_toFilterOnly_includesTheRecordExactlyOnTheUpperBound() {
        Page<NotificationRecord> page = query(new NotificationFilter(null, null, null, null, FEBRUARY));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).allMatch(record -> !record.getCreatedAt().isAfter(FEBRUARY));
    }

    /**
     * Proves the predicates AND together rather than the last one winning: the caller has two
     * transaction alerts and one failure, and exactly one record is both. Either filter applied
     * alone would return more than the single row asserted here.
     */
    @Test
    @DisplayName("Type and status combine, returning only records matching both - [MEANT TO PASS]")
    void forUser_typeAndStatusFilters_returnsOnlyTheRecordMatchingBoth() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                NotificationType.TRANSACTION_ALERT, null, NotificationStatus.FAILED, null, null));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getCreatedAt()).isEqualTo(MARCH);
    }

    @Test
    @DisplayName("Type and a date range combine, returning only records matching both - [MEANT TO PASS]")
    void forUser_typeAndDateRangeFilters_returnsOnlyTheRecordMatchingBoth() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                NotificationType.TRANSACTION_ALERT, null, null, FEBRUARY, FEBRUARY));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("All five filters at once still resolve to the one matching record - [MEANT TO PASS]")
    void forUser_allFiveFiltersAtOnce_returnsTheSingleMatchingRecord() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                NotificationType.TRANSACTION_ALERT, NotificationChannel.EMAIL, NotificationStatus.SENT,
                JANUARY, MARCH));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getCreatedAt()).isEqualTo(FEBRUARY);
    }

    /**
     * The one that matters most: no filter combination may widen the result set past the caller. The
     * other user owns a record matching this filter exactly, so the second query is the mirror image
     * — run as that user, the same filter returns their row and never the caller's identically
     * shaped one.
     */
    @Test
    @DisplayName("A filter never reaches another user's notifications - [MEANT TO PASS]")
    void forUser_filterAlsoMatchingAnotherUsersRecord_returnsOnlyTheCallersRecords() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                NotificationType.TRANSACTION_ALERT, null, NotificationStatus.FAILED, null, null));

        assertThat(page.getContent()).allMatch(record -> record.getUserId() == OWNER);

        Page<NotificationRecord> otherUsersPage = notificationRecordRepository.findAll(
                NotificationSpecifications.forUser(OTHER_USER, new NotificationFilter(
                        NotificationType.TRANSACTION_ALERT, null, NotificationStatus.FAILED, null, null)),
                FIRST_PAGE);

        assertThat(otherUsersPage.getContent()).hasSize(1);
        assertThat(otherUsersPage.getContent().get(0).getUserId()).isEqualTo(OTHER_USER);
    }

    /**
     * A combination nobody's records match must be an empty page — not an error, and not a silent
     * fallback to the unfiltered feed, which is why the total is asserted as well as the content.
     */
    @Test
    @DisplayName("A combination matching nothing returns an empty page - [MEANT TO PASS]")
    void forUser_combinationNoRecordMatches_returnsAnEmptyPage() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                NotificationType.SMS_2FA, NotificationChannel.EMAIL, null, null, null));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    /**
     * Newest-first is part of the endpoint's existing contract, and adding a filter must not quietly
     * reorder the feed.
     */
    @Test
    @DisplayName("Filtered results keep the newest-first ordering - [MEANT TO PASS]")
    void forUser_channelFilterApplied_returnsRecordsStillOrderedNewestFirst() {
        List<NotificationRecord> content = query(new NotificationFilter(
                null, NotificationChannel.EMAIL, null, null, null)).getContent();

        assertThat(content).isSortedAccordingTo(
                (left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()));
    }

    private Page<NotificationRecord> query(NotificationFilter filter) {
        return notificationRecordRepository.findAll(
                NotificationSpecifications.forUser(OWNER, filter), FIRST_PAGE);
    }

    private void save(long userId, NotificationType type, NotificationChannel channel,
                      NotificationStatus status, LocalDateTime createdAt) {
        NotificationRecord record = new NotificationRecord();
        record.setUserId(userId);
        record.setType(type);
        record.setChannel(channel);
        record.setSubject("Test notification");
        record.setMessage("Seeded by " + NotificationFeedFilterTestSuite.class.getSimpleName());
        record.setStatus(status);
        // Set explicitly rather than left to @PrePersist: the date filters are the point of this
        // suite, and "now" for every row would make every range assertion vacuous.
        record.setCreatedAt(createdAt);
        notificationRecordRepository.saveAndFlush(record);
    }
}
