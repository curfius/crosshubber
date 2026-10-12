package com.crosshubber.talenthub.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.talenthub.domain.AnalyticsService;
import com.crosshubber.talenthub.security.ModuleRoles;

/** Analytics REST (P3.3): manager+admin visibility per the app role table. */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

  private final AnalyticsService analyticsService;

  public AnalyticsController(AnalyticsService analyticsService) {
    this.analyticsService = analyticsService;
  }

  @GetMapping("/summary")
  public AnalyticsService.AnalyticsSummary summary(@RequestParam(required = false) String period) {
    ModuleRoles.requireRole(
        List.of(ModuleRoles.MANAGER, ModuleRoles.ADMIN),
        "staffing-manager or staffing-admin role required");
    return analyticsService.summary(period);
  }
}
