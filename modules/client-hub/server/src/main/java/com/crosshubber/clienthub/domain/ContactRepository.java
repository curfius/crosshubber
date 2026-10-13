package com.crosshubber.clienthub.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.crosshubber.clienthub.domain.ContactEntity;

public interface ContactRepository extends JpaRepository<ContactEntity, UUID> {

  java.util.List<ContactEntity> findByAccountIdOrderById(UUID accountId);

  @Query(
      "SELECT c FROM ContactEntity c WHERE "
          + "(:accountId IS NULL OR c.account.id = :accountId) "
          + "AND (:query IS NULL OR LOWER(c.firstName) LIKE LOWER(CONCAT('%', :query, '%')) "
          + "OR LOWER(c.lastName) LIKE LOWER(CONCAT('%', :query, '%')) "
          + "OR LOWER(c.email) LIKE LOWER(CONCAT('%', :query, '%')))")
  org.springframework.data.domain.Page<ContactEntity> search(
      @Param("accountId") UUID accountId,
      @Param("query") String query,
      org.springframework.data.domain.Pageable pageable);
}
