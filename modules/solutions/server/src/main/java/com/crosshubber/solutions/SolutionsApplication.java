package com.crosshubber.solutions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Solutions module — project delivery tracking (remote MFE backend, AI_MODULES_PLAN H2). Bootstrap
 * order: Flyway migrations → serve.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SolutionsApplication {

  public static void main(String[] args) {
    SpringApplication.run(SolutionsApplication.class, args);
  }
}
