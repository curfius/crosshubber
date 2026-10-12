package com.crosshubber.talenthub.web;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.talenthub.connectors.OutboxService;
import com.crosshubber.talenthub.security.ModuleRoles;

/** Connector outbox log view (P4.2): emulated sends/calls are inspectable rows here (admin). */
@RestController
public class OutboxController {

  private final OutboxService outboxService;

  public OutboxController(OutboxService outboxService) {
    this.outboxService = outboxService;
  }

  @GetMapping("/api/settings/outbox")
  public Map<String, Object> list(@RequestParam(required = false) String kind) {
    ModuleRoles.requireAdmin();
    return Map.of(
        "outbox",
        outboxService.latest(kind).stream()
            .map(
                row ->
                    Map.of(
                        "id", String.valueOf(row.getId()),
                        "kind", String.valueOf(row.getKind()),
                        "subject", String.valueOf(row.getSubject()),
                        "body", row.getBody() == null ? Map.of() : row.getBody(),
                        "at", String.valueOf(row.getAt())))
            .collect(Collectors.toList()));
  }
}
