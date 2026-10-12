package com.crosshubber.talenthub.web;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.talenthub.domain.PositionService;
import com.crosshubber.talenthub.domain.RfpDtos;
import com.crosshubber.talenthub.security.AgentPrincipals;
import com.crosshubber.talenthub.security.ModuleRoles;

/** Positions REST (P2.2): recruiter+ writes; 409 on version conflict (house convention). */
@RestController
public class PositionController {

  private final PositionService positionService;

  public PositionController(PositionService positionService) {
    this.positionService = positionService;
  }

  @PostMapping("/api/rfps/{rfpId}/positions")
  public ResponseEntity<RfpDtos.PositionDto> create(
      @PathVariable UUID rfpId, @RequestBody PositionService.PositionCreateRequest body) {
    ModuleRoles.requireRecruiter();
    RfpDtos.PositionDto created =
        positionService.create(rfpId, body, AgentPrincipals.currentName());
    return ResponseEntity.created(URI.create("/api/positions/" + created.id())).body(created);
  }

  @PatchMapping("/api/positions/{id}")
  public RfpDtos.PositionDto patch(
      @PathVariable UUID id, @RequestBody PositionService.PositionPatchRequest body) {
    ModuleRoles.requireRecruiter();
    return positionService.patch(id, body, AgentPrincipals.currentName());
  }
}
