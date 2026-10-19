package com.eia.retrocession.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class RetrocessionOutboxIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CONTRACT = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID RETRO_CONTRACT = UUID.fromString("44444444-4444-7444-8444-444444444444");

    @Autowired
    private RetrocessionService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM retrocession");
    }

    @Test
    void createWritesTheRetrocessionAndCreatedEventTogether() {
        Retrocession retrocession = service.create(request());

        Map<String, Object> event = jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_version, status, tenant_id,
                       payload, headers, partition_key, occurred_at, recorded_at, next_attempt_at
                  FROM outbox_event
                 WHERE aggregate_id = ?
                """, retrocession.id());

        assertThat(event.get("event_type")).isEqualTo("retrocession.retrocession.created");
        assertThat(event.get("aggregate_type")).isEqualTo("Retrocession");
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);
        assertThat(event.get("partition_key")).isEqualTo(retrocession.id().toString());
        assertThat(event.get("occurred_at")).isNotNull();
        assertThat(event.get("recorded_at")).isNotNull();
        assertThat(event.get("next_attempt_at")).isNotNull();

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"retrocessionId\":\"" + retrocession.id() + "\"")
                .contains("\"retrocessionNumber\":\"" + retrocession.retrocessionNumber() + "\"")
                .contains("\"businessBook\":\"ASSUMED\"")
                .contains("\"contractId\":\"" + CONTRACT + "\"")
                .contains("\"retroContractId\":\"" + RETRO_CONTRACT + "\"")
                .contains("\"status\":\"OPEN\"")
                .contains("\"cededPremium\":\"1000.0000\"")
                .contains("\"cededLoss\":\"250.0000\"")
                .contains("\"currency\":\"USD\"");
        String headers = (String) event.get("headers");
        assertThat(headers)
                .contains("\"book\":\"ASSUMED\"")
                .contains("\"vertical\":\"retrocession\"")
                .contains("\"businessKey\":\"" + retrocession.retrocessionNumber() + "\"")
                .contains("\"schemaVersion\":\"1.0.0\"");
    }

    @Test
    void createRollbackLeavesNeitherTheRetrocessionNorTheEvent() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.create(request());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("retrocession")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void statusTransitionWritesTheUpdatedEventInTheSameTransaction() {
        Retrocession retrocession = service.create(request());

        Retrocession updated = service.transition(TENANT, retrocession.id(),
                RetrocessionStatus.RULE_APPLIED, 0);

        assertThat(updated.status()).isEqualTo(RetrocessionStatus.RULE_APPLIED);
        assertThat(updated.version()).isEqualTo(1);

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ?
                 ORDER BY event_sequence
                """, retrocession.id());

        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("event_type")).isEqualTo("retrocession.retrocession.created");
        assertThat(events.get(1).get("event_type")).isEqualTo("retrocession.retrocession.updated");
        assertThat(events.get(1).get("aggregate_version")).isEqualTo(2L);

        String payload = (String) events.get(1).get("payload");
        assertThat(payload)
                .contains("\"changedFields\":[\"status\"]")
                .contains("\"version\":1")
                .contains("\"from\":\"OPEN\"")
                .contains("\"to\":\"RULE_APPLIED\"")
                .contains("\"retrocessionNumber\":\"" + retrocession.retrocessionNumber() + "\"");
    }

    @Test
    void handoffWritesTheHandedOffEventInTheSameTransaction() {
        Retrocession retrocession = service.create(request());
        service.transition(TENANT, retrocession.id(), RetrocessionStatus.RULE_APPLIED, 0);

        Retrocession handedOff = service.recordHandoff(TENANT, retrocession.id(),
                new RecordRetroHandoffRequest(1, "FSRI-RET-ASSUMED-000001", "POSTED",
                        Instant.parse("2026-10-19T12:00:00Z")));

        assertThat(handedOff.status()).isEqualTo(RetrocessionStatus.HANDED_OFF);
        assertThat(handedOff.version()).isEqualTo(2);

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ?
                 ORDER BY event_sequence
                """, retrocession.id());

        assertThat(events).hasSize(3);
        assertThat(events.get(2).get("event_type")).isEqualTo("retrocession.retrocession.handed-off");
        assertThat(events.get(2).get("aggregate_version")).isEqualTo(3L);
        String payload = (String) events.get(2).get("payload");
        assertThat(payload)
                .contains("\"status\":\"HANDED_OFF\"")
                .contains("\"from\":\"RULE_APPLIED\"")
                .contains("\"to\":\"HANDED_OFF\"")
                .contains("\"fsriHandoffId\":\"FSRI-RET-ASSUMED-000001\"")
                .contains("\"fsriStatus\":\"POSTED\"");
    }

    @Test
    void transitionRollbackKeepsTheOldStateAndWritesNoEvent() {
        Retrocession retrocession = service.create(request());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.transition(TENANT, retrocession.id(), RetrocessionStatus.RULE_APPLIED, 0);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM retrocession WHERE id = ?", String.class, retrocession.id()))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM retrocession WHERE id = ?", Integer.class, retrocession.id()))
                .isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = 'retrocession.retrocession.updated'
                """, Integer.class, retrocession.id())).isZero();
    }

    private CreateRetrocessionRequest request() {
        return new CreateRetrocessionRequest(TENANT, "ASSUMED", CONTRACT, RETRO_CONTRACT,
                new BigDecimal("1000.0000"), new BigDecimal("250.0000"), "USD");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
