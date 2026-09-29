package com.crosshubber.solutions.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates requests carrying a portal-minted agent-call token ({@code X-Portal-Agent}).
 * Requests without the header stay anonymous — the security chain then rejects protected routes
 * with 401. Roles from the token become authorities ({@code ROLE_<role>}).
 */
@Component
public class AgentAuthenticationFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Portal-Agent";

  private final AgentCallTokenService tokenService;

  public AgentAuthenticationFilter(AgentCallTokenService tokenService) {
    this.tokenService = tokenService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String token = request.getHeader(HEADER);
    if (token != null && !token.isBlank()) {
      AgentCallTokenService.AgentCallClaims claims = tokenService.verify(token);
      if (claims != null) {
        AgentPrincipal principal = new AgentPrincipal(claims.sub(), claims.name(), claims.roles());
        List<SimpleGrantedAuthority> authorities =
            claims.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
    }
    chain.doFilter(request, response);
  }
}
