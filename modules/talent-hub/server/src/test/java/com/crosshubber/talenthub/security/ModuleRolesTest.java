package com.crosshubber.talenthub.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Role guards (P0.5): who may act, who only reads, anonymous fails all. */
class ModuleRolesTest {

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void asUser(String... roles) {
    AgentPrincipal p = new AgentPrincipal("u1", "Dev User", List.of(roles));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                p,
                null,
                java.util.Arrays.stream(roles)
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList()));
  }

  private static Stream<String> recruiterOrBetter() {
    return Stream.of(ModuleRoles.RECRUITER, ModuleRoles.MANAGER, ModuleRoles.ADMIN);
  }

  @Test
  void managerPassesEverythingExceptNothing() {
    asUser(ModuleRoles.MANAGER);
    assertThatCode(
            () -> {
              ModuleRoles.requireManager();
              ModuleRoles.requireRecruiter();
              ModuleRoles.requireReader();
            })
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @MethodSource("recruiterOrBetter")
  void recruiterManagerAdminPassRecruiterGateAndReads(String role) {
    asUser(role);
    assertThatCode(
            () -> {
              ModuleRoles.requireRecruiter();
              ModuleRoles.requireReader();
            })
        .doesNotThrowAnyException();
    if (role.equals(ModuleRoles.MANAGER)) {
      assertThatCode(ModuleRoles::requireManager).doesNotThrowAnyException();
    } else {
      assertThatThrownBy(ModuleRoles::requireManager).isInstanceOf(ResponseStatusException.class);
    }
  }

  @Test
  void staffingUserIsReadOnly() {
    asUser(ModuleRoles.USER);
    assertThatCode(ModuleRoles::requireReader).doesNotThrowAnyException();
    assertThatThrownBy(ModuleRoles::requireManager).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(ModuleRoles::requireRecruiter).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(ModuleRoles::requireAdmin).isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void adminPassesAdminRecruiterAndReaderButNotManager() {
    asUser(ModuleRoles.ADMIN);
    assertThatCode(
            () -> {
              ModuleRoles.requireAdmin();
              ModuleRoles.requireRecruiter();
              ModuleRoles.requireReader();
            })
        .doesNotThrowAnyException();
    assertThatThrownBy(ModuleRoles::requireManager).isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void anonymousFailsAllGuards() {
    assertThatThrownBy(ModuleRoles::requireManager).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(ModuleRoles::requireRecruiter).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(ModuleRoles::requireReader).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(ModuleRoles::requireAdmin).isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void unrelatedRolesFailAllGuards() {
    asUser("portal-admin");
    assertThatThrownBy(ModuleRoles::requireReader).isInstanceOf(ResponseStatusException.class);
  }
}
