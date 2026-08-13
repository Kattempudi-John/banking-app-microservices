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

// The filtering half of GET /api/v1/notifications, exercised against the real query rather than a
// mocked repository. NotificationPersistenceTestSuite already covers the controller end (parameter
// binding, the response shape, the 400 for an unparseable value); what cannot be proven there is
// whether the predicates actually compose - a Specification that silently ignored a filter, or one
// that dropped the user scoping, would satisfy a mock perfectly.
//
// Runs against the same local Postgres the other suites boot against, not an embedded database: the
// three filtered columns are real PostgreSQL enum types (V1 declares notification_type_enum and
// friends), and enum binding is precisely the thing that would break in a way H2 could never show.
// @DataJpaTest is transactional, so every row written here is rolled back at the end of the test.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        // Same reason InternalTokenSecurityTestSuite caps it: each cached context parks a pool of
        // idle connections against a shared local Postgres for the rest of the run, and the symptom
        // of going over max_connections is an unrelated suite failing with "too many clients".
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

    // Four records for the caller spanning three months, two types, both channels and both statuses,
    // so each filter below has something it must include AND something it must exclude.
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
    void testNoFilters_ReturnsEverythingForTheCaller() {
        Page<NotificationRecord> page = query(NotificationFilter.none());

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).allMatch(record -> record.getUserId() == OWNER);
    }

    @Test
    @DisplayName("The type filter alone narrows to that type - [MEANT TO PASS]")
    void testTypeFilterAlone() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                NotificationType.TRANSACTION_ALERT, null, null, null, null));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).allMatch(record -> record.getType() == NotificationType.TRANSACTION_ALERT);
    }

    @Test
    @DisplayName("The channel filter alone narrows to that channel - [MEANT TO PASS]")
    void testChannelFilterAlone() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                null, NotificationChannel.SMS, null, null, null));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getChannel()).isEqualTo(NotificationChannel.SMS);
    }

    @Test
    @DisplayName("The status filter alone narrows to that status - [MEANT TO PASS]")
    void testStatusFilterAlone() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                null, null, NotificationStatus.FAILED, null, null));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    // Both bounds are inclusive, which is why these are the exact timestamps of the seeded rows
    // rather than a day either side of them - an exclusive bound would drop the boundary record and
    // this is the assertion that would notice.
    @Test
    @DisplayName("The from filter alone is an inclusive lower bound - [MEANT TO PASS]")
    void testFromFilterAlone_IsInclusive() {
        Page<NotificationRecord> page = query(new NotificationFilter(null, null, null, FEBRUARY, null));

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getContent()).allMatch(record -> !record.getCreatedAt().isBefore(FEBRUARY));
    }

    @Test
    @DisplayName("The to filter alone is an inclusive upper bound - [MEANT TO PASS]")
    void testToFilterAlone_IsInclusive() {
        Page<NotificationRecord> page = query(new NotificationFilter(null, null, null, null, FEBRUARY));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).allMatch(record -> !record.getCreatedAt().isAfter(FEBRUARY));
    }

    // The combination that proves the predicates AND together rather than the last one winning: the
    // caller has two transaction alerts and one failure, and exactly one record is both.
    @Test
    @DisplayName("Type and status combine, returning only records matching both - [MEANT TO PASS]")
    void testTypeAndStatusCombined() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                NotificationType.TRANSACTION_ALERT, null, NotificationStatus.FAILED, null, null));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getCreatedAt()).isEqualTo(MARCH);
    }

    @Test
    @DisplayName("Type and a date range combine, returning only records matching both - [MEANT TO PASS]")
    void testTypeAndDateRangeCombined() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                NotificationType.TRANSACTION_ALERT, null, null, FEBRUARY, FEBRUARY));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("All five filters at once still resolve to the one matching record - [MEANT TO PASS]")
    void testEveryFilterAtOnce() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                NotificationType.TRANSACTION_ALERT, NotificationChannel.EMAIL, NotificationStatus.SENT,
                JANUARY, MARCH));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getCreatedAt()).isEqualTo(FEBRUARY);
    }

    // The one that matters most: no filter combination may widen the result set past the caller. The
    // other user owns a record matching this filter exactly, and it must not appear.
    @Test
    @DisplayName("A filter never reaches another user's notifications - [MEANT TO PASS]")
    void testFilterNeverCrossesUsers() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                NotificationType.TRANSACTION_ALERT, null, NotificationStatus.FAILED, null, null));

        assertThat(page.getContent()).allMatch(record -> record.getUserId() == OWNER);

        // And the mirror image: the same query run as the other user sees only their own row, not the
        // caller's identically-shaped one.
        Page<NotificationRecord> otherUsersPage = notificationRecordRepository.findAll(
                NotificationSpecifications.forUser(OTHER_USER, new NotificationFilter(
                        NotificationType.TRANSACTION_ALERT, null, NotificationStatus.FAILED, null, null)),
                FIRST_PAGE);

        assertThat(otherUsersPage.getContent()).hasSize(1);
        assertThat(otherUsersPage.getContent().get(0).getUserId()).isEqualTo(OTHER_USER);
    }

    // A filter combination nobody's records match is an empty page, not an error and not a fallback
    // to the unfiltered feed.
    @Test
    @DisplayName("A combination matching nothing returns an empty page - [MEANT TO PASS]")
    void testImpossibleCombinationReturnsEmpty() {
        Page<NotificationRecord> page = query(new NotificationFilter(
                NotificationType.SMS_2FA, NotificationChannel.EMAIL, null, null, null));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    // The default sort is part of the endpoint's existing contract - newest first - and filtering must
    // not quietly reorder the feed.
    @Test
    @DisplayName("Filtered results keep the newest-first ordering - [MEANT TO PASS]")
    void testFilteredResultsKeepDefaultSort() {
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
