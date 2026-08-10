package com.example.notificationservice.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.notificationservice.model.NotificationChannel;
import com.example.notificationservice.model.NotificationRecord;
import com.example.notificationservice.model.NotificationStatus;
import com.example.notificationservice.model.NotificationType;
import com.example.notificationservice.repository.NotificationRecordRepository;

// consumes the SMS_2FA_REQUESTED event auth-service publishes on every login that needs a 2FA
// code (see AuthSecurityService.publishSmsEvent) - this is the listener that was missing entirely,
// which is why 2FA codes never actually reached a phone before this class existed
@Service
public class TwoFactorSmsListener {

    private static final Logger logger = LoggerFactory.getLogger(TwoFactorSmsListener.class);

    private final NotificationProviderService notificationProviderService;
    private final NotificationRecordRepository notificationRecordRepository;

    public TwoFactorSmsListener(NotificationProviderService notificationProviderService,
                                 NotificationRecordRepository notificationRecordRepository) {
        this.notificationProviderService = notificationProviderService;
        this.notificationRecordRepository = notificationRecordRepository;
    }

    @KafkaListener(topics = "notification-events", groupId = "notification-service-group")
    public void consumeSmsRequest(Map<String, Object> event) {
        try {
            if (!"SMS_2FA_REQUESTED".equals(event.get("action"))) {
                return;
            }

            Long userId = Long.valueOf(event.get("userId").toString());
            String phoneNumber = (String) event.get("phoneNumber");
            String code = (String) event.get("code");
            String message = String.format("Your verification code is %s. It expires in 5 minutes.", code);

            // dispatchSms's @Recover swallows the exception after exhausting retries (same return
            // type as the retried method), so its boolean return - not a caught exception - is
            // what actually tells us whether this ultimately succeeded.
            boolean dispatched = notificationProviderService.dispatchSms(phoneNumber, message);
            NotificationStatus status = dispatched ? NotificationStatus.SENT : NotificationStatus.FAILED;

            if (dispatched) {
                logger.info("Notification Service: 2FA SMS dispatched to phone number ending in {}",
                        phoneNumber.substring(Math.max(0, phoneNumber.length() - 4)));
            }

            persistRecord(userId, message, status);
        } catch (Exception e) {
            logger.error("Failed to process 2FA SMS event", e);
        }
    }

    private void persistRecord(Long userId, String message, NotificationStatus status) {
        NotificationRecord record = new NotificationRecord();
        record.setUserId(userId);
        record.setType(NotificationType.SMS_2FA);
        record.setChannel(NotificationChannel.SMS);
        record.setMessage(message);
        record.setStatus(status);
        notificationRecordRepository.save(record);
    }
}
