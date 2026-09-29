package com.crosshubber.solutions.docs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.crosshubber.solutions.config.SolutionsProperties;

/**
 * In-repo stub adapter (default): serves a small deterministic corpus so the RAG pipeline,
 * citations and settings flow are exercisable without Azure credentials. Recognized refs: {@code
 * fake:proposal-scope}, {@code fake:architecture-notes}, {@code fake:steering-minutes}; anything
 * else returns placeholder text.
 */
@Component
@ConditionalOnProperty(name = "solutions.docsource.kind", havingValue = "fake")
public class FakeDocumentSource implements DocumentSource {

  private final SolutionsProperties props;

  public FakeDocumentSource(SolutionsProperties props) {
    this.props = props;
  }

  @Override
  public DocumentContent fetchDocument(String documentRef) {
    String name =
        documentRef.startsWith("fake:") ? documentRef.substring("fake:".length()) : documentRef;
    String text = corpus(name);
    return new DocumentContent(name, "text/plain", text.length(), text);
  }

  private String corpus(String name) {
    return switch (name) {
      case "proposal-scope" ->
          """
          Proposal — ERP Rollout for Acme Manufacturing.

          Scope: implement the finance and warehouse modules, migrate legacy data, integrate
          with the existing CRM. Team: one delivery lead, two backend engineers (Java, Spring),
          one frontend engineer (Angular), one QA. Pricing: fixed price 180000 EUR across three
          milestones. Timeline: kickoff in week 1, milestone 1 (finance live) week 8, milestone 2
          (warehouse live) week 16, milestone 3 (CRM integration and go-live) week 24. Budget
          burn is tracked monthly; steering committee reviews scope changes.""";
      case "architecture-notes" ->
          """
          Architecture notes — ERP Rollout.

          Integration uses REST APIs with Kafka for warehouse events. Database migration runs
          per milestone with rollback scripts. Deployment on Kubernetes; environments dev, staging,
          production. Security review scheduled before milestone 2. Risk: legacy CRM API is
          undocumented — mitigation is a contract-first proxy.""";
      case "steering-minutes" ->
          """
          Steering committee minutes — week 12.

          Status: milestone 1 delivered, milestone 2 at risk — data migration is two weeks late,
          budget burn at 55 percent against 50 percent plan. Decision: add one contractor for
          migration scripts, re-baseline timeline for milestone 3. Next review in two weeks.""";
      default ->
          "Placeholder content for document '"
              + name
              + "'. The solutions module runs the fake document source (solutions.docsource.kind="
              + props.getDocsource().getKind()
              + "); replace it with the OneDrive adapter once Azure credentials are provisioned.";
    };
  }
}
