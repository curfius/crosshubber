package com.crosshubber.talenthub.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.talenthub.domain.CandidateService;
import com.crosshubber.talenthub.security.AgentPrincipals;
import com.crosshubber.talenthub.security.ModuleRoles;

/** Talent pool REST (P2.6): reads for all staffing roles; writes recruiter-or-better. */
@RestController
public class CandidateController {

  private final CandidateService candidateService;

  public CandidateController(CandidateService candidateService) {
    this.candidateService = candidateService;
  }

  @GetMapping("/api/candidates")
  public Map<String, Object> list(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String availability,
      @RequestParam(required = false, name = "stale") String staleFlag) {
    ModuleRoles.requireReader();
    boolean staleOnly = "true".equals(staleFlag);
    List<CandidateService.CandidateDto> result =
        candidateService.list(query, kind, availability, staleOnly);
    return Map.of("candidates", result);
  }

  @GetMapping("/api/candidates/{id}")
  public CandidateService.CandidateDetailDto detail(@PathVariable UUID id) {
    ModuleRoles.requireReader();
    return candidateService.detail(id);
  }

  @PostMapping("/api/candidates")
  public ResponseEntity<CandidateService.CandidateDto> create(
      @RequestBody CandidateService.CandidateCreateRequest body) {
    ModuleRoles.requireRecruiter();
    CandidateService.CandidateDto created =
        candidateService.create(body, AgentPrincipals.currentName());
    return ResponseEntity.created(URI.create("/api/candidates/" + created.id())).body(created);
  }

  @PatchMapping("/api/candidates/{id}")
  public CandidateService.CandidateDto patch(
      @PathVariable UUID id, @RequestBody CandidateService.CandidatePatchRequest body) {
    ModuleRoles.requireRecruiter();
    return candidateService.patch(id, body, AgentPrincipals.currentName());
  }

  @PostMapping("/api/candidates/{id}/enrich")
  public CandidateService.CandidateDto enrich(@PathVariable UUID id) {
    ModuleRoles.requireRecruiter();
    return candidateService.enrich(id, AgentPrincipals.currentName());
  }

  /** CV scan (P2.6): fetch document by ref through the docsource port + naive parse. */
  public record ScanRequest(String documentRef, String title) {}

  @PostMapping("/api/candidates/scan")
  public CandidateService.CandidateDto scan(@RequestBody ScanRequest body) {
    ModuleRoles.requireRecruiter();
    String ref = body == null ? null : body.documentRef();
    String title = body == null ? null : body.title();
    return candidateService.scan(ref, title, AgentPrincipals.currentName());
  }
}
