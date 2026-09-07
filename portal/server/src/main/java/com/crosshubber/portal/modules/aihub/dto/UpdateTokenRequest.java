package com.crosshubber.portal.modules.aihub.dto;

import java.util.List;

public record UpdateTokenRequest(
    String name, String apiKey, Boolean enabled, List<Object> models) {}
