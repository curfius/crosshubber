package com.crosshubber.portal.shell.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Dashboard service hint. {@code url} is always present (null for services without a web UI);
 * {@code hint} is omitted when absent.
 */
@JsonInclude(Include.NON_NULL)
public record ShellServiceDto(
    String key, String name, @JsonInclude(Include.ALWAYS) String url, String color, String hint) {}
