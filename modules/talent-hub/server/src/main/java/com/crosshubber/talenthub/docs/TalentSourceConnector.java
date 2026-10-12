package com.crosshubber.talenthub.docs;

/**
 * Module-owned sourcing-inbox port (P2.6): intake from external talent sources (job boards, ATS) as
 * fixture-like objects. MVP ships the emulated connector only; real adapters land later with the P4
 * connector registry.
 */
public interface TalentSourceConnector
    extends com.crosshubber.talenthub.connectors.ModuleConnector {

  @Override
  default com.crosshubber.talenthub.connectors.ConnectorCategory category() {
    return com.crosshubber.talenthub.connectors.ConnectorCategory.TALENT_SOURCE;
  }

  default String kind() {
    return "emulated";
  }

  /** One intake item: a ready-to-claim candidate snapshot. */
  record InboxItem(String ref, String name, String headline, String documentRef, String note) {}

  /** Claim result: keeps the display name + document ref to scan. */
  record ClaimResult(String ref, String sourceRef, String name) {}

  /** Current unclaimed intake snapshot. */
  java.util.List<InboxItem> inbox();

  /**
   * Claims an intake item (removes it from the inbox); returns its ref.
   *
   * @throws IllegalArgumentException when the ref is unknown or already claimed
   */
  ClaimResult claim(String ref);
}
