package com.crosshubber.talenthub.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * J/E/V fit verdict (D7): one dimension verdict per Job / Environment / Value fit plus an overall
 * verdict (worst-of). Shape stored on the process {@code fit} jsonb: {dimensions: {job: {verdict,
 * rationale, score?}, environment: {...}, value: {...}}, overall, engine, at}.
 */
public record FitVerdict(
    Map<String, DimensionVerdict> dimensions, String overall, String engine, String at) {

  public record DimensionVerdict(String verdict, String rationale, String score) {}

  public static final String STRONG = "strong";
  public static final String MAYBE = "maybe";
  public static final String SKIP = "skip";

  private static final Map<String, Integer> ORDER = Map.of(STRONG, 2, MAYBE, 1, SKIP, 0);

  public static DimensionVerdict dimension(String verdict, String rationale, String score) {
    if (!ORDER.containsKey(verdict)) {
      throw new IllegalArgumentException("verdict must be strong|maybe|skip");
    }
    return new DimensionVerdict(verdict, rationale, score);
  }

  /** Worst-of aggregation: any skip → skip; else any maybe → maybe; all strong → strong. */
  public static String overallOf(
      DimensionVerdict job, DimensionVerdict environment, DimensionVerdict value) {
    int worst = 2;
    for (DimensionVerdict dimension : new DimensionVerdict[] {job, environment, value}) {
      if (dimension == null) {
        worst = 0;
        continue;
      }
      worst = Math.min(worst, ORDER.getOrDefault(dimension.verdict(), 0));
    }
    return worst == 0 ? SKIP : worst == 1 ? MAYBE : STRONG;
  }

  /** Assembles the stored jsonb shape with the engine + timestamp stamped. */
  public static FitVerdict of(
      String engine, DimensionVerdict job, DimensionVerdict environment, DimensionVerdict value) {
    Map<String, DimensionVerdict> dimensions = new LinkedHashMap<>();
    dimensions.put("job", job);
    dimensions.put("environment", environment);
    dimensions.put("value", value);
    return new FitVerdict(
        dimensions, overallOf(job, environment, value), engine, Instant.now().toString());
  }
}
