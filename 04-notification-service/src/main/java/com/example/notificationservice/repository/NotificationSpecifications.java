package com.example.notificationservice.repository;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.example.notificationservice.dto.NotificationFilter;
import com.example.notificationservice.model.NotificationRecord;

import jakarta.persistence.criteria.Predicate;

/**
 * Builds the filtered form of the notifications feed query.
 *
 * <p>A specification composes what a derived query name cannot: five optional narrowings mean 32
 * combinations, and a {@code findByUserIdAndTypeAndChannelAnd...} method would be needed per
 * combination. Each filter contributes a predicate only when supplied, so an absent filter is an
 * absent predicate rather than a null bind.
 *
 * <p>That is also why this is not the "IS NULL OR" {@code @Query} pattern used in account-service:
 * that pattern must give an unused null parameter a concrete type for the Postgres driver, and
 * {@code type}, {@code channel} and {@code status} here are real PostgreSQL enum columns bound as
 * {@code NAMED_ENUM}, where casting the bind to a string has no sensible meaning.
 */
public final class NotificationSpecifications {

    private NotificationSpecifications() {
    }

    /**
     * Builds a specification matching one user's notifications, narrowed by whichever filters were
     * supplied.
     *
     * <p>The user predicate is ANDed in unconditionally, so no combination of client-supplied
     * filters can remove or widen it. That is why the id is a separate mandatory argument rather
     * than a sixth field on the filter — the filter is built from query parameters, this is not.
     *
     * <p>Both date bounds are inclusive: a user filtering "from the 1st to the 3rd" means the whole
     * range as they would describe it, and an exclusive upper bound silently drops notifications
     * landing exactly on the boundary they typed.
     *
     * @param userId must come from the caller's JWT claim; a {@code null} here matches no rows
     *     rather than every row
     * @param filter never {@code null}; use {@code NotificationFilter.none()} for no narrowing, in
     *     which case the result matches on user alone
     * @return a specification usable with any {@code JpaSpecificationExecutor} query method
     */
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
