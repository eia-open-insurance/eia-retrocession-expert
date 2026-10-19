package com.eia.retrocession.expert;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Failed FS-RI attempt metadata. Persisting it must not change the lifecycle
 * status; the case stays retryable.
 */
public record RecordRetroHandoffFailureRequest(
        @NotNull @Min(0) Integer expectedVersion,
        @NotBlank @Size(max = 1000) String fsriLastError
) {}
