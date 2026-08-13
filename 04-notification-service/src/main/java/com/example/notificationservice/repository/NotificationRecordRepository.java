package com.example.notificationservice.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.example.notificationservice.model.NotificationRecord;

@Repository
public interface NotificationRecordRepository extends JpaRepository<NotificationRecord, Long>,
        JpaSpecificationExecutor<NotificationRecord> {

    Page<NotificationRecord> findByUserId(Long userId, Pageable pageable);

    // JpaSpecificationExecutor is what the filtered feed reads through (see
    // NotificationQueryService.buildSpecification). A Specification rather than the "IS NULL OR ..."
    // @Query this project uses elsewhere (account-service's findByAccountIdInWithFilters) for one
    // concrete reason: type, channel and status are real PostgreSQL enum columns here, bound as
    // NAMED_ENUM, and the CAST(:param AS string) that pattern needs to give an unused null parameter
    // a type has no sensible meaning for an enum-typed bind. A Specification simply omits the
    // predicate for a filter that wasn't supplied, so there is no null to type in the first place.
}
