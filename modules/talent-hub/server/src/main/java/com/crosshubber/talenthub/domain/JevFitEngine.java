package com.crosshubber.talenthub.domain;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.crosshubber.talenthub.settings.ModuleSettingsService;

/**
 * External JEV decision API engine (D7): one POST per candidate↔position with the three typed
 * questions; credentials/timeouts come from settings (masked key) with env fallback. Configured
 * response contract: {"job": {"verdict","rationale","score"?}, "environment": {...}, "value":
 * {...}}.
 */
@Component
public class JevFitEngine implements FitEngine {

  private final ModuleSettingsService settings;
  private final RestClient.Builder restClientBuilder;

  public JevFitEngine(ModuleSettingsService settings, RestClient.Builder restClientBuilder) {
    this.settings = settings;
    this.restClientBuilder = restClientBuilder;
  }

  @Override
  public String key() {
    return "jev";
  }

  @Override
  @SuppressWarnings("unchecked")
  public FitVerdict assess(
      CandidateProfileEntity candidate, PositionRequirements requirements, String actor)
      throws Exception {
    ModuleSettingsService.EffectiveJev jev = settings.effectiveJev();
    if (!jev.isConfigured()) {
      throw new IllegalStateException(
          "jev engine not configured (settings or TALENTHUB_JEV_* env)");
    }
    HttpClientSettings clientSettings =
        HttpClientSettings.defaults()
            .withConnectTimeout(Duration.ofSeconds(5))
            .withReadTimeout(Duration.ofMillis(jev.timeoutMs()));
    RestClient restClient =
        restClientBuilder
            .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(clientSettings))
            .build();
    Map<String, Object> response =
        restClient
            .post()
            .uri(jev.baseUrl().replaceAll("/+$", "") + "/assess")
            .header("Authorization", "Bearer " + jev.apiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "candidate",
                    Map.of(
                        "name", candidate.getName(),
                        "skills", candidate.getSkills(),
                        "seniority", candidate.getSeniority(),
                        "languages", candidate.getLanguages(),
                        "availability",
                            candidate.getAvailability() == null ? "" : candidate.getAvailability()),
                    "requirements",
                    requirements.raw()))
            .retrieve()
            .body(Map.class);
    if (response == null || !response.containsKey("job")) {
      throw new IllegalStateException("jev response did not include dimension verdicts");
    }
    return FitVerdict.of(
        key(),
        dimensionOf(response, "job"),
        dimensionOf(response, "environment"),
        dimensionOf(response, "value"));
  }

  @SuppressWarnings("unchecked")
  private static FitVerdict.DimensionVerdict dimensionOf(Map<String, Object> response, String key) {
    Map<String, Object> dimension = (Map<String, Object>) response.get(key);
    if (dimension == null) {
      return FitVerdict.dimension(FitVerdict.MAYBE, "engine did not report this dimension", null);
    }
    Object verdict = dimension.get("verdict");
    Object rationale = dimension.get("rationale");
    Object score = dimension.get("score");
    return FitVerdict.dimension(
        verdict == null ? FitVerdict.MAYBE : verdict.toString(),
        rationale == null ? "no rationale" : rationale.toString(),
        score == null ? null : score.toString());
  }
}
