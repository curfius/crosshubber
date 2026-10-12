package com.crosshubber.talenthub.web;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.talenthub.domain.RfpDtos;
import com.crosshubber.talenthub.domain.SubmissionService;
import com.crosshubber.talenthub.security.AgentPrincipals;
import com.crosshubber.talenthub.security.ModuleRoles;

/** Submissions REST (P2.4): batch send + response recording (recruiter+). */
@RestController
public class SubmissionController {

  private final SubmissionService submissionService;

  public SubmissionController(SubmissionService submissionService) {
    this.submissionService = submissionService;
  }

  @PostMapping("/api/rfps/{rfpId}/submissions")
  public ResponseEntity<RfpDtos.SubmissionDto> create(
      @PathVariable UUID rfpId, @RequestBody SubmissionService.CreateSubmissionRequest body) {
    ModuleRoles.requireRecruiter();
    RfpDtos.SubmissionDto created =
        submissionService.create(rfpId, body, AgentPrincipals.currentName());
    return ResponseEntity.created(URI.create("/api/submissions/" + created.id())).body(created);
  }

  @PostMapping("/api/submissions/{id}/response")
  public RfpDtos.SubmissionDto respond(
      @PathVariable UUID id, @RequestBody SubmissionService.SubmissionResponseRequest body) {
    ModuleRoles.requireRecruiter();
    return submissionService.respond(id, body, AgentPrincipals.currentName());
  }
}
