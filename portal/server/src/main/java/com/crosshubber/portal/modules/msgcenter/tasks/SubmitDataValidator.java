package com.crosshubber.portal.modules.msgcenter.tasks;

import java.util.Map;

import com.crosshubber.portal.modules.msgcenter.domain.McMessageEntity;

import tools.jackson.databind.JsonNode;

/**
 * Submit-path data validation seam (plan §4 checkpoint 3): implementations are the sole authority
 * between raw form data and a recorded/published response.
 */
public interface SubmitDataValidator {

  /**
   * Validates {@code data} against the task's stored field specs.
   *
   * @return the validated payload as stored in {@code mc_task_responses.data_json}
   * @throws org.springframework.web.server.ResponseStatusException 400 on any violation
   */
  Map<String, Object> validate(
      McMessageEntity message, JsonNode data, String actorSub, String actorName);
}
