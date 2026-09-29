package com.crosshubber.staffing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Staffing module — RFP/RFQ intake + CV matching (remote MFE backend, AI_MODULES_PLAN H3).
 * Bootstrap order: Flyway migrations → serve.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class StaffingApplication {

  public static void main(String[] args) {
    SpringApplication.run(StaffingApplication.class, args);
  }
}
