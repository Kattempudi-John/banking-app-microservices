package com.example.notificationservice.model;

/**
 * The outcome of a dispatch attempt, recorded whether or not it succeeded.
 *
 * <p>Backed by a real PostgreSQL enum type rather than a varchar, so adding a constant here needs an
 * accompanying {@code ALTER TYPE} migration or Hibernate fails to bind it.
 *
 * <p>{@code FAILED} covers both an exhausted provider retry and a user with no address on file; it
 * is written and served back to the user rather than hidden, because "we owed you this and could not
 * send it" is what the feed exists to show. There is no pending or retrying state — a record is
 * written once, after the attempt has finished.
 */
public enum NotificationStatus {
    SENT,
    FAILED
}
