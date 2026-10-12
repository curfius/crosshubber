package com.crosshubber.talenthub.domain;

import java.util.List;

import org.springframework.stereotype.Service;

import com.crosshubber.talenthub.docs.DocumentService;
import com.crosshubber.talenthub.docs.TalentSourceConnector;
import com.crosshubber.talenthub.events.PortalEventPublisher;
import com.crosshubber.talenthub.security.ModuleRoles;

/**
 * Sourcing inbox (P2.6): emulated intake → claim → create a candidate in the pool (with a scan of
 * the linked fixture CV) → publish candidate.added. Reads recruiter-or-better (sourcing is an
 * acting surface); the created candidate publishes outside the transaction.
 */
@Service
public class SourcingInboxService {

  private final TalentSourceConnector connector;
  private final CandidateService candidateService;
  private final DocumentService documentService;
  private final PortalEventPublisher publisher;

  public SourcingInboxService(
      TalentSourceConnector connector,
      CandidateService candidateService,
      DocumentService documentService,
      PortalEventPublisher publisher) {
    this.connector = connector;
    this.candidateService = candidateService;
    this.documentService = documentService;
    this.publisher = publisher;
  }

  public List<TalentSourceConnector.InboxItem> inbox() {
    return connector.inbox();
  }

  /** Claims an intake item and creates the pool candidate; fails hard on double-claim. */
  public CandidateService.CandidateDto claim(String ref, String actor) {
    TalentSourceConnector.ClaimResult claimed = connector.claim(ref);
    String text;
    try {
      text = documentService.resolve(claimed.sourceRef()).text();
    } catch (Exception e) {
      throw new IllegalStateException("scan failed for " + claimed.sourceRef());
    }
    CandidateService.CandidateDto created =
        candidateService.ingest(claimed.sourceRef(), claimed.name(), text);
    publisher.publishMessage(
        "candidate.added",
        "Candidate added: " + created.name(),
        "source: " + ref,
        "info",
        List.of(ModuleRoles.MANAGER, ModuleRoles.RECRUITER, ModuleRoles.ADMIN),
        null);
    return created;
  }
}
