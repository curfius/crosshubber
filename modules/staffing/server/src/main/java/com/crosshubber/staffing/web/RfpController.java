package com.crosshubber.staffing.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.staffing.domain.CandidateProfileEntity;
import com.crosshubber.staffing.domain.RfpEntity;
import com.crosshubber.staffing.domain.RfpStatus;
import com.crosshubber.staffing.domain.ShortlistEntity;
import com.crosshubber.staffing.domain.StaffingService;
import com.crosshubber.staffing.security.AgentPrincipals;

/** Staffing REST surface (consumed by the module UI via its agent-call token). */
@RestController
@RequestMapping("/api")
public class RfpController {

  public record CreateRfpRequest(
      String client,
      String title,
      String kind,
      Instant deadline,
      Map<String, Object> requirements,
      String specDocRef) {}

  public record TransitionRequest(String toStatus, Map<String, Object> requirements) {}

  public record ScanCvRequest(String documentRef, String title) {}

  public record ShortlistRequest(List<UUID> candidateIds) {}

  private final StaffingService service;

  public RfpController(StaffingService service) {
    this.service = service;
  }

  @GetMapping("/rfps")
  public Map<String, Object> listRfps(@RequestParam(required = false) String status) {
    RfpStatus filter = status == null ? null : RfpStatus.fromString(status);
    return Map.of("rfps", service.listRfps(filter));
  }

  @PostMapping("/rfps")
  public ResponseEntity<RfpEntity> createRfp(@RequestBody CreateRfpRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            service.createRfp(
                body.client(),
                body.title(),
                body.kind(),
                body.deadline(),
                body.requirements(),
                body.specDocRef(),
                AgentPrincipals.currentName()));
  }

  @GetMapping("/rfps/{id}")
  public RfpEntity getRfp(@PathVariable UUID id) {
    return service.getRfp(id);
  }

  @PostMapping("/rfps/{id}/transitions")
  public RfpEntity transition(@PathVariable UUID id, @RequestBody TransitionRequest body) {
    return service.transition(id, body.toStatus(), body.requirements());
  }

  @PostMapping("/candidates/scan")
  public ResponseEntity<CandidateProfileEntity> scanCv(@RequestBody ScanCvRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.scanCv(body.documentRef(), body.title()));
  }

  @GetMapping("/candidates")
  public Map<String, Object> listCandidates() {
    return Map.of("candidates", service.listCandidates());
  }

  @PostMapping("/rfps/{id}/shortlists")
  public ResponseEntity<ShortlistEntity> createShortlist(
      @PathVariable UUID id, @RequestBody ShortlistRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.createShortlist(id, body.candidateIds(), AgentPrincipals.currentName()));
  }

  @GetMapping("/rfps/{id}/shortlists")
  public Map<String, Object> listShortlists(@PathVariable UUID id) {
    return Map.of("shortlists", service.listShortlists(id));
  }

  @GetMapping("/rfps/{id}/runs")
  public Map<String, Object> listMatchRuns(@PathVariable UUID id) {
    return Map.of("runs", service.listMatchRuns(id));
  }
}
