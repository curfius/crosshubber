package com.crosshubber.clienthub.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.crosshubber.clienthub.domain.AccountEntity;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

  boolean existsByKey(String key);

  @Query(
      "SELECT a FROM AccountEntity a WHERE "
          + "(:query IS NULL OR LOWER(a.name) LIKE LOWER(CONCAT('%', :query, '%')) "
          + "OR LOWER(a.key) LIKE LOWER(CONCAT('%', :query, '%')) "
          + "OR LOWER(a.industry) LIKE LOWER(CONCAT('%', :query, '%')))")
  org.springframework.data.domain.Page<AccountEntity> search(
      @Param("query") String query, org.springframework.data.domain.Pageable pageable);

  @Query(
      "SELECT a FROM AccountEntity a WHERE a.status = :status "
          + "AND (:query IS NULL OR LOWER(a.name) LIKE LOWER(CONCAT('%', :query, '%')) "
          + "OR LOWER(a.key) LIKE LOWER(CONCAT('%', :query, '%')) "
          + "OR LOWER(a.industry) LIKE LOWER(CONCAT('%', :query, '%')))")
  org.springframework.data.domain.Page<AccountEntity> searchByStatus(
      @Param("status") String status,
      @Param("query") String query,
      org.springframework.data.domain.Pageable pageable);

  @Query("SELECT COUNT(c) FROM ContactEntity c WHERE c.account.id = :accountId")
  long countContacts(@Param("accountId") UUID accountId);
}
