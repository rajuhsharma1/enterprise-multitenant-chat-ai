package com.enterprise.chat.repository;

import com.enterprise.chat.domain.entity.OrganizationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<OrganizationEntity, java.util.UUID> {}
