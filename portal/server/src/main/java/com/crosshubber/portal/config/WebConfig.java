package com.crosshubber.portal.config;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * SPA fallback — serves {@code index.html} for non-API GET routes.
 *
 * <p>Mirrors {@code portal/src/server.ts:88} express.static + SPA fallback.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

  @Override
  public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
    configurer.setDefaultTimeout(300_000);
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    log.info("[portal] configuring SPA fallback resource handler");
    registry
        .addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .resourceChain(true)
        .addResolver(
            new PathResourceResolver() {
              @Override
              protected Resource getResource(String resourcePath, Resource location)
                  throws IOException {
                Resource requested = location.createRelative(resourcePath);
                if (requested.exists() && requested.isReadable()) {
                  return requested;
                }
                // For SPA routes (GET not /api/), serve index.html as fallback
                // so unknown paths like .well-known probes don't throw errors
                if (!resourcePath.startsWith("api/")) {
                  Resource index = new ClassPathResource("/static/index.html");
                  if (index.exists()) {
                    return index;
                  }
                }
                return null;
              }
            });
  }
}
