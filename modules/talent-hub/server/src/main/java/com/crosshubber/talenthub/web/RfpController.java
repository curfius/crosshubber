package com.crosshubber.talenthub.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.talenthub.domain.RfpCaptureService;
import com.crosshubber.talenthub.domain.RfpDtos;
import com.crosshubber.talenthub.domain.RfpPhase;
import com.crosshubber.talenthub.domain.RfpQueryService;
import com.crosshubber.talenthub.domain.RfpRepository;
import com.crosshubber.talenthub.domain.RfpWorkflowService;
import com.crosshubber.talenthub.security.AgentPrincipals;
import com.crosshubber.talenthub.security.ModuleRoles;

/**
 * RFP REST (P1.4 capture/approve + P2.1 reads): controllers stay thin — routing, status codes, role
 * gates; logic lives in the services.
 */
@RestController
public class RfpController {

  private final RfpCaptureService captureService;
  private final RfpWorkflowService workflowService;
  private final RfpQueryService queryService;
  private final RfpRepository rfpRepository;

  public RfpController(
      RfpCaptureService captureService,
      RfpWorkflowService workflowService,
      RfpQueryService queryService,
      RfpRepository rfpRepository) {
    this.captureService = captureService;
    this.workflowService = workflowService;
    this.queryService = queryService;
    this.rfpRepository = rfpRepository;
  }

  @GetMapping("/api/rfps")
  public Map<String, Object> list(
      @RequestParam(required = false) String phase,
      @RequestParam(required = false) UUID clientId,
      @RequestParam(required = false) String serviceType,
      @RequestParam(required = false) String kind) {
    ModuleRoles.requireReader();
    RfpPhase phaseFilter = null;
    if (phase != null && !phase.isBlank()) {
      phaseFilter = RfpPhase.fromString(phase);
    }
    return Map.of("rfps", queryService.list(phaseFilter, clientId, serviceType, kind));
  }

  @GetMapping("/api/rfps/{id}")
  public RfpDtos.RfpDetailDto detail(@PathVariable UUID id) {
    ModuleRoles.requireReader();
    return queryService.detail(id);
  }

  @PostMapping("/api/rfps/capture")
  public ResponseEntity<?> capture(@RequestBody RfpCaptureService.CaptureRequest body) {
    ModuleRoles.requireManager();
    RfpDtos.RfpDto created = captureService.capture(body, AgentPrincipals.currentName());
    return ResponseEntity.created(URI.create("/api/rfps/" + created.id())).body(created);
  }

  @PostMapping("/api/rfps/{id}/approve")
  public RfpDtos.RfpDto approve(
      @PathVariable UUID id, @RequestBody(required = false) RfpWorkflowService.ApproveEdits edits) {
    ModuleRoles.requireManager();
    return RfpDtos.toDto(workflowService.approve(id, AgentPrincipals.currentName(), edits).rfp());
  }

  @GetMapping("/api/rfps/drafts")
  public Map<String, Object> drafts() {
    ModuleRoles.requireRole(
        List.of(ModuleRoles.MANAGER, ModuleRoles.ADMIN),
        "staffing-manager or staffing-admin role required");
    return Map.of(
        "rfps",
        rfpRepository.findByPhaseOrderByCreatedAtDesc(RfpPhase.DRAFT).stream()
            .map(RfpDtos::toDto)
            .toList());
  }

  /** Close body: {confirm: true, reason?} — server rejects without explicit confirmation (D3). */
  public record CloseRequest(boolean confirm, String reason) {}

  /** Reject body: {reason} — required (D4). */
  public record RejectRequest(String reason) {}

  @PostMapping("/api/rfps/{id}/close")
  public RfpDtos.RfpDto close(@PathVariable UUID id, @RequestBody CloseRequest body) {
    ModuleRoles.requireManager();
    boolean confirm = body != null && body.confirm();
    String reason = body == null ? null : body.reason();
    return RfpDtos.toDto(
        workflowService.close(id, confirm, reason, AgentPrincipals.currentName()).rfp());
  }

  @PostMapping("/api/rfps/{id}/reject")
  public RfpDtos.RfpDto reject(@PathVariable UUID id, @RequestBody RejectRequest body) {
    ModuleRoles.requireManager();
    String reason = body == null ? null : body.reason();
    return RfpDtos.toDto(workflowService.reject(id, reason, AgentPrincipals.currentName()).rfp());
  }

  @PostMapping("/api/rfps/{id}/archive")
  public RfpDtos.RfpDto archive(@PathVariable UUID id) {
    ModuleRoles.requireManager();
    return RfpDtos.toDto(workflowService.archive(id, AgentPrincipals.currentName()).rfp());
  }
}
