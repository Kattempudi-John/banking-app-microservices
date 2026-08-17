package com.example.profileservice.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * A customer's notification preferences: the large-transaction alert threshold and the daily-summary
 * opt-in with its delivery timezone and hour.
 *
 * <p>{@code dailySummaryHour} is a whole hour {@code 0}-{@code 23} in {@code timezone}, not a
 * {@code LocalTime}: the notification-service job wakes once an hour, so finer precision would be a
 * promise the sender cannot keep. The range is validated at the API edge
 * ({@code UpdateDailySummaryRequestDto}) rather than on this entity, because a constraint here would
 * surface as a 500 from the persistence layer instead of a 400.
 *
 * <p>The row is keyed by its own generated id with {@code userId} as a separate column, so lookups
 * by user go through {@code PreferenceRepository.findByUserId}.
 */
@Entity
@Table(name = "user_preferences")
public class UserPreferenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private BigDecimal alertThresholdAmount;
    private Boolean dailySummaryEnabled;
    private String timezone;

    private Integer dailySummaryHour;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public BigDecimal getAlertThresholdAmount() { return alertThresholdAmount; }
    public void setAlertThresholdAmount(BigDecimal alertThresholdAmount) { this.alertThresholdAmount = alertThresholdAmount; }

    public Boolean getDailySummaryEnabled() { return dailySummaryEnabled; }
    public void setDailySummaryEnabled(Boolean dailySummaryEnabled) { this.dailySummaryEnabled = dailySummaryEnabled; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public Integer getDailySummaryHour() { return dailySummaryHour; }
    public void setDailySummaryHour(Integer dailySummaryHour) { this.dailySummaryHour = dailySummaryHour; }
}