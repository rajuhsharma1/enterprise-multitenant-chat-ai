package com.enterprise.chat.repository;

import com.enterprise.chat.domain.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, java.util.UUID> {}
