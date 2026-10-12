package com.crosshubber.talenthub.connectors;

/** Connector categories (P4.1); selection is runtime, one active kind per category. */
public enum ConnectorCategory {
  CRM("crm"),
  TALENT_SOURCE("talentSource"),
  DOCUMENT_STORE("documentStore"),
  MESSAGING("messaging"),
  HRIS("hris");

  private final String key;

  ConnectorCategory(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** Category lookup by key; null-safe for stored settings. */
  public static ConnectorCategory fromKey(String key) {
    for (ConnectorCategory category : values()) {
      if (category.key.equals(key)) {
        return category;
      }
    }
    return null;
  }
}
