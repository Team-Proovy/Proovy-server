package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.MessageAsset;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageAssetRepository extends JpaRepository<MessageAsset, Long> {
}

