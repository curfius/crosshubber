package com.crosshubber.portal.bootstrap;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link TenantMetaEntity}. */
@Repository
public interface TenantMetaRepository extends JpaRepository<TenantMetaEntity, String> {}
