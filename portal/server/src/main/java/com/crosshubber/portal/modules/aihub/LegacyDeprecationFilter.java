package com.crosshubber.portal.modules.aihub;

import java.io.IOException;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Marks responses served from the deprecated pre-consolidation paths ({@code /api/llm}, {@code
 * /api/chat}) — mirrors the deprecationMark middleware in {@code
 * portal/src/modules/ai-hub/ai-hub.routes.ts} (D5).
 */
@Component
@Order(Integer.MAX_VALUE - 1)
public class LegacyDeprecationFilter implements Filter {

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (request instanceof HttpServletRequest req && response instanceof HttpServletResponse res) {
      String uri = req.getRequestURI();
      if (uri.startsWith("/api/llm") || uri.startsWith("/api/chat")) {
        res.setHeader("Deprecation", "true");
        res.setHeader("Sunset", "Wed, 30 Sep 2026 00:00:00 GMT");
        res.setHeader("Link", "</api/ai-hub>; rel=\"successor-version\"");
      }
    }
    chain.doFilter(request, response);
  }
}
