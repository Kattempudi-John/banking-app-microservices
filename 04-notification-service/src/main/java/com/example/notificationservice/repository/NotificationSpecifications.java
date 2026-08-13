package com.example.notificationservice.repository;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.example.notificationservice.dto.NotificationFilter;
import com.example.notificationservice.model.NotificationRecord;

import jakarta.persistence.criteria.Predicate;

// Builds the filtered form of the notifications feed query.
//
// A Specification composes what a derived query name cannot: five optional narrowings mean 32
// combinations, and findByUserIdAndTypeAndChannelAndStatusAndCreatedAt... would need one method per
// combination. Each filter contributes a predicate only when it was actually supplied, so an absent
// filter is an absent predicate.
//
// This is also why it isn't the "CAST(:param AS string) IS NULL OR ..." @Query the project uses in
// account-service (findByAccountIdInWithFilters): that pattern has to give an unused null parameter
// a concrete type for the Postgres driver, and type/channel/status here are real PostgreSQL enum
// columns bound as NAMED_ENUM, where casting the bind to a string has no sensible meaning. With a
// Specification there is no null to type in the first place.
public final class NotificationSpecifications {

    private NotificationSpecifications() {
    }

    // userId is a separate mandatory argument rather than another field on the filter: it comes from
    // the caller's JWT, it is ANDed into every query unconditionally, and no combination of
    // client-supplied filters can remove or widen it.
    public static Specification<NotificationRecord> forUser(Long userId, NotificationFilter filter) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(criteriaBuilder.equal(root.get("userId"), userId));

            if (filter.type() != null) {
                predicates.add(criteriaBuilder.equal(root.get("type"), filter.type()));
            }
            if (filter.channel() != null) {
                predicates.add(criteriaBuilder.equal(root.get("channel"), filter.channel()));
            }
            if (filter.status() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), filter.status()));
            }
            // Both bounds inclusive: a user filtering "from the 1st to the 3rd" means the whole of
            // the range as they would describe it, and an exclusive upper bound silently drops the
            // notifications that landed exactly on the boundary they typed.
            if (filter.from() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), filter.to()));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
