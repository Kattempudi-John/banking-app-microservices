package com.example.notificationservice.dto;

import java.time.LocalDateTime;

import com.example.notificationservice.model.NotificationChannel;
import com.example.notificationservice.model.NotificationStatus;
import com.example.notificationservice.model.NotificationType;

// The five optional narrowings GET /api/v1/notifications accepts, carried together so the query
// service's signature stays readable and so "were any supplied?" is one question asked in one place
// rather than a five-way null check repeated at each call site.
//
// Deliberately holds no user id. The caller's own id comes from their JWT, never from a query
// parameter, and keeping it out of this record means there is no field here that could ever widen a
// result set past the caller's own notifications - see NotificationSpecifications.forUser, which
// takes it as a separate, mandatory argument.
public record NotificationFilter(NotificationType type,
                                 NotificationChannel channel,
                                 NotificationStatus status,
                                 LocalDateTime from,
                                 LocalDateTime to) {

    public static NotificationFilter none() {
        return new NotificationFilter(null, null, null, null, null);
    }

    public boolean isEmpty() {
        return type == null && channel == null && status == null && from == null && to == null;
    }
}
