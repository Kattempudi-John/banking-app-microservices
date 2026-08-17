package com.example.notificationservice.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.example.notificationservice.model.NotificationRecord;

/**
 * Persists and reads notification records.
 *
 * <p>Extends {@code JpaSpecificationExecutor} because the filtered feed reads through
 * {@code NotificationSpecifications} rather than through the "IS NULL OR" {@code @Query} pattern
 * used elsewhere in this project: {@code type}, {@code channel} and {@code status} are real
 * PostgreSQL enum columns bound as {@code NAMED_ENUM}, and the cast that pattern needs to give an
 * unused null parameter a type has no sensible meaning for an enum-typed bind. A specification
 * simply omits the predicate for an absent filter, so there is no null to type.
 */
@Repository
public interface NotificationRecordRepository extends JpaRepository<NotificationRecord, Long>,
        JpaSpecificationExecutor<NotificationRecord> {

    /**
     * Returns one user's notifications, unfiltered.
     *
     * <p>This is the path the unfiltered feed keeps using rather than an equivalent single-predicate
     * specification, so the default request the page makes on every open is demonstrably unchanged
     * by the filtering feature.
     *
     * @param userId must come from the caller's JWT claim, never a request parameter — it is the
     *     only thing scoping the result to its owner
     * @param pageable ordering comes from here, so an unsorted pageable yields database order rather
     *     than newest first
     * @return a page that is empty, not {@code null}, when the user has no notifications
     */
    Page<NotificationRecord> findByUserId(Long userId, Pageable pageable);
}
