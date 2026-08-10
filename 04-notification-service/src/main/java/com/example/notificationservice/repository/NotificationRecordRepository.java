package com.example.notificationservice.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.notificationservice.model.NotificationRecord;

@Repository
public interface NotificationRecordRepository extends JpaRepository<NotificationRecord, Long> {

    Page<NotificationRecord> findByUserId(Long userId, Pageable pageable);
}
