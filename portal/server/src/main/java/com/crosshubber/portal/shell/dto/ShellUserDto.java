package com.crosshubber.portal.shell.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Authenticated-user block of the shell config; {@code email} omitted when absent. */
@JsonInclude(Include.NON_NULL)
public record ShellUserDto(String sub, String name, String email, List<String> roles) {}
