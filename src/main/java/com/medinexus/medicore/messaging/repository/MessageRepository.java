package com.medinexus.medicore.messaging.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.medinexus.medicore.messaging.entity.Message;

public interface MessageRepository extends JpaRepository<Message, Long> {
}
