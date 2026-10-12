package com.crosshubber.talenthub.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.talenthub.domain.CandidateService;
import com.crosshubber.talenthub.domain.SourcingInboxService;
import com.crosshubber.talenthub.security.AgentPrincipals;
import com.crosshubber.talenthub.security.ModuleRoles;

/** Sourcing inbox REST (P2.6): emulated intake + claim (recruiter+). */
@RestController
public class SourcingInboxController {

  private final SourcingInboxService inboxService;

  public SourcingInboxController(SourcingInboxService inboxService) {
    this.inboxService = inboxService;
  }

  @GetMapping("/api/sourcing/inbox")
  public Map<String, Object> inbox() {
    ModuleRoles.requireRecruiter();
    return Map.of("inbox", inboxService.inbox());
  }

  @PostMapping("/api/sourcing/inbox/{ref}/claim")
  public ResponseEntity<CandidateService.CandidateDto> claim(@PathVariable String ref) {
    ModuleRoles.requireRecruiter();
    CandidateService.CandidateDto created = inboxService.claim(ref, AgentPrincipals.currentName());
    return ResponseEntity.status(201).body(created);
  }
}
