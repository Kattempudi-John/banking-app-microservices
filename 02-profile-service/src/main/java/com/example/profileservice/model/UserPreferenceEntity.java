package com.example.profileservice.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

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

    // The hour of the day, 0-23 in the user's own timezone above, that the daily summary goes out.
    // Integer rather than a LocalTime or a minute-precision field: the notification-service job wakes
    // up once an hour, so anything finer than whole hours would be a promise the sender cannot keep.
    // The 0..23 range is validated at the API edge (UpdateDailySummaryRequestDto), not here - a
    // @Min/@Max on the entity would surface as a 500 from the persistence layer rather than a 400.
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