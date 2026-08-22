package com.labdms.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

public record ReviewDecisionRequest(
    @NotBlank String decision,
    String comments
) {}
