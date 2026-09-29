package com.crosshubber.staffing.docs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.crosshubber.staffing.config.StaffingProperties;

/**
 * In-repo stub adapter (default): serves a small deterministic CV corpus so ingestion, extraction
 * and matching are exercisable without Azure credentials. Recognized refs: {@code fake:cv-ana},
 * {@code fake:cv-bruno}, {@code fake:cv-carla}; anything else returns placeholder text.
 */
@Component
@ConditionalOnProperty(name = "staffing.docsource.kind", havingValue = "fake")
public class FakeDocumentSource implements DocumentSource {

  private final StaffingProperties props;

  public FakeDocumentSource(StaffingProperties props) {
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
      case "cv-ana" ->
          """
          CV — Ana Pereira. Senior Java backend engineer.
          Summary: senior engineer with ten years of experience in Java and Spring Boot,
          microservices and REST APIs. Strong SQL and PostgreSQL. Angular on the frontend.
          Docker and Kubernetes in production. Languages: Portuguese (native), English (fluent).
          Availability: immediate.""";
      case "cv-bruno" ->
          """
          CV — Bruno Costa. Frontend engineer.
          Summary: five years of React and TypeScript, design systems and testing. Some
          JavaScript tooling and REST integration. Languages: Portuguese (native), English (good).
          Availability: one month notice.""";
      case "cv-carla" ->
          """
          CV — Carla Mendes. Principal platform engineer.
          Summary: principal-level engineer focused on AWS, Kubernetes and Terraform. Java and
          Python for tooling. Kafka streaming experience. Languages: English (fluent), Spanish.
          Availability: notice period of three months.""";
      default ->
          "Placeholder content for document '"
              + name
              + "'. The staffing module runs the fake document source (staffing.docsource.kind="
              + props.getDocsource().getKind()
              + "); replace it with the OneDrive adapter once Azure credentials are provisioned.";
    };
  }
}
