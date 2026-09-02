package com.crosshubber.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Portal backend (Spring Boot port of portal/src/server.ts).
 *
 * <p>Bootstrap order mirrors the Node version: 1) load tenant config, 2) run Flyway migrations, 3)
 * reconcile tenant, 4) init IdP, 5) serve.
 */
@SpringBootApplication
@EnableScheduling
public class PortalApplication {

  public static void main(String[] args) {
    SpringApplication.run(PortalApplication.class, args);
  }
}
