package com.eia.retrocession.expert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateRetrocessionRequest(
        @NotNull UUID tenantId,
        @NotBlank @Pattern(regexp = "ASSUMED", message = "must be ASSUMED") String businessBook,
        @NotNull UUID contractId,
        @NotNull UUID retroContractId,
        @NotNull @DecimalMin(value = "0.0000", message = "must be zero or greater")
        @Digits(integer = 15, fraction = 4) BigDecimal cededPremium,
        @NotNull @DecimalMin(value = "0.0000", message = "must be zero or greater")
        @Digits(integer = 15, fraction = 4) BigDecimal cededLoss,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a three-letter currency code")
        String currency
) {}
