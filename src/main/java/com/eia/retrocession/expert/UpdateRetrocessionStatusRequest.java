package com.eia.retrocession.expert;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateRetrocessionStatusRequest(
        @NotNull RetrocessionStatus status,
        @NotNull @Min(0) Integer version
) {}
