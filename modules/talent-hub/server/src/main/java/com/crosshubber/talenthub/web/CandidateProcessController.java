package com.crosshubber.talenthub.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.talenthub.domain.CandidateProcessService;
import com.crosshubber.talenthub.domain.RfpDtos;
import com.crosshubber.talenthub.security.AgentPrincipals;
import com.crosshubber.talenthub.security.ModuleRoles;

/** Candidate-process REST (P2.3): recruiter+ writes; validation server-side. */
@RestController
public class CandidateProcessController {

  private final CandidateProcessService processService;

  public CandidateProcessController(CandidateProcessService processService) {
    this.processService = processService;
  }

  @PostMapping("/api/positions/{id}/processes")
  public ResponseEntity<RfpDtos.ProcessDto> open(
      @PathVariable java.util.UUID id,
      @RequestBody CandidateProcessService.OpenProcessRequest body) {
    ModuleRoles.requireRecruiter();
    RfpDtos.ProcessDto created = processService.open(id, body, AgentPrincipals.currentName());
    return ResponseEntity.created(URI.create("/api/processes/" + created.id())).body(created);
  }

  @PatchMapping("/api/processes/{id}/stage")
  public RfpDtos.ProcessDto transition(
      @PathVariable java.util.UUID id,
      @RequestBody CandidateProcessService.StageChangeRequest body) {
    ModuleRoles.requireRecruiter();
    return processService.transition(id, body, AgentPrincipals.currentName());
  }

  @PostMapping("/api/processes/{id}/assessment")
  public RfpDtos.ProcessDto assess(
      @PathVariable java.util.UUID id,
      @RequestBody(required = false) CandidateProcessService.AssessRequest body) {
    ModuleRoles.requireRecruiter();
    return processService.assess(id, body, AgentPrincipals.currentName());
  }
}
