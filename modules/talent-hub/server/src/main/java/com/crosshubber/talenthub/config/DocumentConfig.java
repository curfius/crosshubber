package com.crosshubber.talenthub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.crosshubber.talenthub.docs.DocumentService;

/** Wires module-owned local document storage from config. */
@Configuration
public class DocumentConfig {

  @Bean
  DocumentService.DocumentServiceConfig documentServiceConfig(TalentHubProperties props) {
    return new DocumentService.DocumentServiceConfig(props.getDocuments().getDir());
  }
}
