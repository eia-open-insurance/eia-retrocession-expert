package com.eia.retrocession.expert;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expert-side invariant enforcement. The domain owns business decisions, but the
 * expert never persists a record that breaks the ASSUMED-book, amount, currency,
 * or lifecycle invariants (SPEC 3.6, 11.10.1, 11.10.3, 11.10.5).
 *
 * <p>Hand-off state is persisted only through the dedicated methods, which
 * combine the lifecycle guard, the optimistic version check, and the FS-RI
 * acknowledgement in one transaction. A plain status update can never write
 * {@code HANDOFF_PENDING} or {@code HANDED_OFF}.</p>
 */
@Service
public class RetrocessionService {
    private final RetrocessionRepository repository;

    public RetrocessionService(RetrocessionRepository repository) {
        this.repository = repository;
    }

    public List<Retrocession> list(UUID tenantId) {
        return repository.findAll(tenantId);
    }

    public Retrocession get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("Retrocession not found: " + id));
    }

    public Retrocession create(CreateRetrocessionRequest request) {
        requireAssumedBook(request.businessBook());
        requireNonNegative("cededPremium", request.cededPremium());
        requireNonNegative("cededLoss", request.cededLoss());
        String currency = requireCurrency(request.currency());
        return repository.create(request.tenantId(), request.contractId(), request.retroContractId(),
                request.cededPremium(), request.cededLoss(), currency);
    }

    @Transactional
    public Retrocession transition(UUID tenantId, UUID id, RetrocessionStatus target, int expectedVersion) {
        Retrocession current = get(tenantId, id);
        requireVersion(current, expectedVersion);
        if (!current.status().canTransitionTo(target)) {
            throw ApiException.badRequest("INVALID_STATUS_TRANSITION",
                    "Retrocession " + id + " cannot move from " + current.status() + " to " + target);
        }
        if (target == RetrocessionStatus.HANDOFF_PENDING || target == RetrocessionStatus.HANDED_OFF) {
            throw ApiException.badRequest("HANDOFF_REQUIRED",
                    "Retrocession " + id + " can move to " + target + " only through the FS-RI handoff endpoint");
        }
        return repository.updateStatus(tenantId, id, target, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("Retrocession " + id + " was modified concurrently"));
    }

    /**
     * Persist the FS-RI acknowledgement and move to HANDED_OFF. The repository
     * performs both in a single optimistic UPDATE so a concurrent writer cannot
     * leave a handed-off case without its FS-RI reference.
     */
    @Transactional
    public Retrocession recordHandoff(UUID tenantId, UUID id, RecordRetroHandoffRequest request) {
        Retrocession current = get(tenantId, id);
        int expectedVersion = requireVersion(request.expectedVersion());
        requireVersion(current, expectedVersion);
        requireHandoffReady(current);
        String fsriHandoffId = requireText("fsriHandoffId", request.fsriHandoffId(), 64);
        String fsriStatus = requireText("fsriStatus", request.fsriStatus(), 32);
        if (request.fsriHandedOffAt() == null) {
            throw ApiException.validation("fsriHandedOffAt", "must not be null");
        }
        Instant handedOffAt = request.fsriHandedOffAt();
        return repository.recordHandoff(tenantId, id, expectedVersion,
                        fsriHandoffId, fsriStatus, handedOffAt)
                .orElseThrow(() -> ApiException.conflict("Retrocession " + id + " was modified concurrently"));
    }

    /**
     * Record a failed FS-RI attempt without changing the lifecycle status. The
     * case remains retryable and its version is unchanged, so the retry reuses
     * the same transition version and idempotency key.
     */
    @Transactional
    public Retrocession recordHandoffFailure(UUID tenantId, UUID id,
                                             RecordRetroHandoffFailureRequest request) {
        Retrocession current = get(tenantId, id);
        int expectedVersion = requireVersion(request.expectedVersion());
        requireVersion(current, expectedVersion);
        requireHandoffReady(current);
        String lastError = requireText("fsriLastError", request.fsriLastError(), 1000);
        return repository.recordHandoffFailure(tenantId, id, expectedVersion, lastError)
                .orElseThrow(() -> ApiException.conflict("Retrocession " + id + " was modified concurrently"));
    }

    private static void requireVersion(Retrocession current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw ApiException.conflict("Retrocession " + current.id() + " was modified; expected version "
                    + expectedVersion + " but found " + current.version());
        }
    }

    private static int requireVersion(Integer expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) {
            throw ApiException.validation("expectedVersion", "must be zero or greater");
        }
        return expectedVersion;
    }

    private static void requireHandoffReady(Retrocession current) {
        if (current.status() != RetrocessionStatus.RULE_APPLIED
                && current.status() != RetrocessionStatus.REJECTED) {
            throw ApiException.badRequest("INVALID_STATUS_TRANSITION",
                    "Retrocession " + current.id() + " cannot be handed off from " + current.status());
        }
    }

    private static String requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw ApiException.validation(field, "must not be blank");
        }
        if (value.length() > maxLength) {
            throw ApiException.validation(field, "must be at most " + maxLength + " characters");
        }
        return value;
    }

    private static void requireAssumedBook(String businessBook) {
        if (businessBook == null || !"ASSUMED".equals(businessBook)) {
            throw ApiException.badRequest("BOOK_SCOPE_VIOLATION",
                    "Retrocession is an ASSUMED-only vertical; businessBook must be ASSUMED");
        }
    }

    private static void requireNonNegative(String field, BigDecimal amount) {
        if (amount == null) {
            throw ApiException.validation(field, "must not be null");
        }
        if (amount.signum() < 0) {
            throw ApiException.validation(field, "must be zero or greater");
        }
    }

    private static String requireCurrency(String currency) {
        if (currency == null || !currency.matches("[A-Za-z]{3}")) {
            throw ApiException.validation("currency", "must be a three-letter currency code");
        }
        return currency.toUpperCase(Locale.ROOT);
    }
}
