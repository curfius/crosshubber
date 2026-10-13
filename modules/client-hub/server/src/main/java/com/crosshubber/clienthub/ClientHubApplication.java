package com.crosshubber.clienthub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Client Hub module — CRM accounts and contacts. Standalone Spring Boot app served in
 * the portal as an iframe; identity arrives via portal-minted agent-call tokens.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ClientHubApplication {

  public static void main(String[] args) {
    SpringApplication.run(ClientHubApplication.class, args);
  }
}
