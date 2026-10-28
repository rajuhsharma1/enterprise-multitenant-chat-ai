package com.enterprise.chat.repository;

import com.enterprise.chat.domain.entity.ChannelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelRepository extends JpaRepository<ChannelEntity, java.util.UUID> {}
