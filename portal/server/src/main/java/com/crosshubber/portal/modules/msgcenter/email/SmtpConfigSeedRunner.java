package com.crosshubber.portal.modules.msgcenter.email;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Boot seed for the SMTP config (Phase 7): {@code PORTAL_SMTP_*} applies insert-if-absent AFTER the
 * reconciler — admin edits are never clobbered. Fail-soft: seeding errors log and never block boot
 * (the channel simply stays off until configured).
 */
@Component
@Order(200)
public class SmtpConfigSeedRunner implements ApplicationRunner {

  private final SmtpConfigService smtpConfigService;

  public SmtpConfigSeedRunner(SmtpConfigService smtpConfigService) {
    this.smtpConfigService = smtpConfigService;
  }

  @Override
  public void run(ApplicationArguments args) {
    smtpConfigService.seedFromEnv();
  }
}
