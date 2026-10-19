package com.eia.retrocession.expert;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * Expert-side invariant enforcement. The domain owns business decisions, but the
 * expert never persists a record that breaks the ASSUMED-book, amount, currency,
 * or lifecycle invariants (SPEC 3.6, 11.10.1, 11.10.3, 11.10.5).
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

    public Retrocession transition(UUID tenantId, UUID id, RetrocessionStatus target, int expectedVersion) {
        Retrocession current = get(tenantId, id);
        if (current.version() != expectedVersion) {
            throw ApiException.conflict("Retrocession " + id + " was modified; expected version "
                    + expectedVersion + " but found " + current.version());
        }
        if (!current.status().canTransitionTo(target)) {
            throw ApiException.badRequest("INVALID_STATUS_TRANSITION",
                    "Retrocession " + id + " cannot move from " + current.status() + " to " + target);
        }
        return repository.updateStatus(tenantId, id, target, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("Retrocession " + id + " was modified concurrently"));
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
