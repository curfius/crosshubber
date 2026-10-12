package com.crosshubber.talenthub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Talent Hub module — staffing RFP/talent platform v2 (parallel-run replacement for staffing;
 * STAFFING_IMPLEMENTATION_PLAN.md). Bootstrap order: Flyway migrations → serve.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TalentHubApplication {

  public static void main(String[] args) {
    SpringApplication.run(TalentHubApplication.class, args);
  }
}
