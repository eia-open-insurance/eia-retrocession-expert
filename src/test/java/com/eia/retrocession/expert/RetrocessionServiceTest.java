package com.eia.retrocession.expert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrocessionServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CONTRACT = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID RETRO_CONTRACT = UUID.fromString("44444444-4444-7444-8444-444444444444");

    @Mock
    private RetrocessionRepository repository;

    @InjectMocks
    private RetrocessionService service;

    private static CreateRetrocessionRequest request(String book, String premium, String loss, String currency) {
        return new CreateRetrocessionRequest(TENANT, book, CONTRACT, RETRO_CONTRACT,
                new BigDecimal(premium), new BigDecimal(loss), currency);
    }

    private static Retrocession retrocession(RetrocessionStatus status, int version) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new Retrocession(ID, TENANT, "ASSUMED", "RET-000000001", CONTRACT, RETRO_CONTRACT, status,
                new BigDecimal("1000.0000"), new BigDecimal("250.0000"), "USD", version, now, now);
    }

    @Test
    void rejectsNegativeCededLossEvenWhenBeanValidationIsBypassed() {
        assertThatThrownBy(() -> service.create(request("ASSUMED", "10.0000", "-0.0001", "USD")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("VALIDATION");
                    assertThat(ex.getDetails()).containsEntry("cededLoss", "must be zero or greater");
                });

        verify(repository, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsNullCededPremium() {
        assertThatThrownBy(() -> service.create(
                new CreateRetrocessionRequest(TENANT, "ASSUMED", CONTRACT, RETRO_CONTRACT, null,
                        BigDecimal.ZERO, "USD")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getDetails()).containsEntry("cededPremium", "must not be null"));
    }

    @Test
    void rejectsDirectBook() {
        assertThatThrownBy(() -> service.create(request("DIRECT", "10.0000", "0.0000", "USD")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("BOOK_SCOPE_VIOLATION");
                    assertThat(ex.getMessage()).contains("ASSUMED-only");
                });

        verify(repository, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsInvalidCurrency() {
        assertThatThrownBy(() -> service.create(request("ASSUMED", "10.0000", "0.0000", "US")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getDetails()).containsEntry("currency",
                                "must be a three-letter currency code"));
    }

    @Test
    void normalizesCurrencyBeforePersisting() {
        when(repository.create(eq(TENANT), eq(CONTRACT), eq(RETRO_CONTRACT), any(), any(), eq("USD")))
                .thenReturn(retrocession(RetrocessionStatus.OPEN, 0));

        Retrocession created = service.create(request("ASSUMED", "1000.0000", "250.0000", "usd"));

        assertThat(created.status()).isEqualTo(RetrocessionStatus.OPEN);
        assertThat(created.businessBook()).isEqualTo("ASSUMED");
        verify(repository).create(eq(TENANT), eq(CONTRACT), eq(RETRO_CONTRACT),
                eq(new BigDecimal("1000.0000")), eq(new BigDecimal("250.0000")), eq("USD"));
    }

    @Test
    void missingRetrocessionRaisesNotFound() {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(TENANT, ID))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                });
    }

    @Test
    void appliesLegalStatusTransition() {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(retrocession(RetrocessionStatus.OPEN, 0)));
        when(repository.updateStatus(TENANT, ID, RetrocessionStatus.RULE_APPLIED, 0))
                .thenReturn(Optional.of(retrocession(RetrocessionStatus.RULE_APPLIED, 1)));

        Retrocession updated = service.transition(TENANT, ID, RetrocessionStatus.RULE_APPLIED, 0);

        assertThat(updated.status()).isEqualTo(RetrocessionStatus.RULE_APPLIED);
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void rejectsSkippedLifecycleStep() {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(retrocession(RetrocessionStatus.OPEN, 0)));

        assertThatThrownBy(() -> service.transition(TENANT, ID, RetrocessionStatus.HANDED_OFF, 0))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_STATUS_TRANSITION");
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                });

        verify(repository, never()).updateStatus(any(), any(), any(), anyInt());
    }

    @Test
    void rejectsStaleVersion() {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(retrocession(RetrocessionStatus.OPEN, 3)));

        assertThatThrownBy(() -> service.transition(TENANT, ID, RetrocessionStatus.RULE_APPLIED, 2))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("CONFLICT");
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                });
    }

    @Test
    void statusLifecycleIsTerminalAfterConfirmedAndCancelled() {
        assertThat(RetrocessionStatus.CONFIRMED.isTerminal()).isTrue();
        assertThat(RetrocessionStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(RetrocessionStatus.OPEN.isTerminal()).isFalse();
        assertThat(RetrocessionStatus.HANDOFF_PENDING.canTransitionTo(RetrocessionStatus.REJECTED)).isTrue();
        assertThat(RetrocessionStatus.REJECTED.canTransitionTo(RetrocessionStatus.HANDOFF_PENDING)).isTrue();
        assertThat(RetrocessionStatus.CONFIRMED.canTransitionTo(RetrocessionStatus.OPEN)).isFalse();
    }
}
