package com.crosshubber.portal.modules.navigation.layout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link NavigationLayoutEntity} (singleton id=1). */
@Repository
public interface NavigationLayoutRepository
    extends JpaRepository<NavigationLayoutEntity, Integer> {}
