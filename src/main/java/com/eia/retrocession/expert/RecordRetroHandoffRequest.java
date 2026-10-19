package com.eia.retrocession.expert;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * FS-RI acknowledgement to persist on an existing retro case. The case only
 * moves to HANDED_OFF when this write succeeds.
 */
public record RecordRetroHandoffRequest(
        @NotNull @Min(0) Integer expectedVersion,
        @NotBlank @Size(max = 64) String fsriHandoffId,
        @NotBlank @Size(max = 32) String fsriStatus,
        @NotNull Instant fsriHandedOffAt
) {}
