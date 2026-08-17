package com.example.notificationservice.dto;

import java.time.LocalDateTime;

import com.example.notificationservice.model.NotificationChannel;
import com.example.notificationservice.model.NotificationStatus;
import com.example.notificationservice.model.NotificationType;

/**
 * Carries the five optional narrowings {@code GET /api/v1/notifications} accepts.
 *
 * <p>Every component is nullable and a {@code null} means "do not narrow on this", so all 32
 * combinations are valid, including all-null. {@code from} and {@code to} are both inclusive bounds
 * on {@code createdAt}.
 *
 * <p>The record deliberately holds no user id. The caller's id comes from their JWT, so keeping it
 * out means no field here can ever widen a result set past the caller's own notifications — see
 * {@code NotificationSpecifications.forUser}, which takes it as a separate mandatory argument.
 *
 * @param type optional notification kind
 * @param channel optional delivery channel
 * @param status optional delivery outcome
 * @param from optional inclusive lower bound on creation time
 * @param to optional inclusive upper bound on creation time
 */
public record NotificationFilter(NotificationType type,
                                 NotificationChannel channel,
                                 NotificationStatus status,
                                 LocalDateTime from,
                                 LocalDateTime to) {

    /**
     * Returns the filter that narrows nothing.
     *
     * @return an instance for which {@link #isEmpty()} is {@code true}
     */
    public static NotificationFilter none() {
        return new NotificationFilter(null, null, null, null, null);
    }

    /**
     * Reports whether no narrowing at all was supplied.
     *
     * <p>The query service branches on this to keep the unfiltered feed — the request the page makes
     * every time it opens — on the plain derived query rather than routing it through a
     * {@code Specification} that would build the identical single-predicate query.
     *
     * @return {@code true} when all five components are {@code null}
     */
    public boolean isEmpty() {
        return type == null && channel == null && status == null && from == null && to == null;
    }
}
