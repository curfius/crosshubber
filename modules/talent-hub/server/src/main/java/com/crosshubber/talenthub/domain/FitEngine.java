package com.crosshubber.talenthub.domain;

/**
 * J/E/V fit assessment port (D7): the scoring backend is selectable at runtime — {@code rules}
 * (default, deterministic), {@code jev} (external decision API), {@code llm} (module LLM).
 * Selection comes from module settings with per-call override (P3.2).
 */
public interface FitEngine {

  /** Engine key as stored on results ({@code rules|jev|llm}). */
  String key();

  /**
   * Assesses one candidate against one position's requirements.
   *
   * @throws Exception on transport/HTTP failure (callers convert to fail-soft outcomes)
   */
  FitVerdict assess(
      CandidateProfileEntity candidate, PositionRequirements requirements, String actor)
      throws Exception;
}
