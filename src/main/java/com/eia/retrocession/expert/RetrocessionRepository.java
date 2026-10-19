package com.eia.retrocession.expert;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RetrocessionRepository {
    private final JdbcTemplate jdbc;

    public RetrocessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Retrocession> MAPPER = (rs, rowNum) -> new Retrocession(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("business_book"),
            rs.getString("retrocession_number"),
            rs.getObject("contract_id", UUID.class),
            rs.getObject("retro_contract_id", UUID.class),
            RetrocessionStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("ceded_premium"),
            rs.getBigDecimal("ceded_loss"),
            rs.getString("currency"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getString("fsri_handoff_id"),
            rs.getString("fsri_status"),
            instant(rs.getTimestamp("fsri_handed_off_at")),
            rs.getString("fsri_last_error")
    );

    private static final String COLUMNS = """
            id, tenant_id, business_book, retrocession_number, contract_id, retro_contract_id, status,
            ceded_premium, ceded_loss, currency, version, created_at, updated_at,
            fsri_handoff_id, fsri_status, fsri_handed_off_at, fsri_last_error
            """;

    public List<Retrocession> findAll(UUID tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM retrocession WHERE tenant_id = ? ORDER BY created_at DESC",
                MAPPER, tenantId);
    }

    public Optional<Retrocession> findById(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM retrocession WHERE tenant_id = ? AND id = ?",
                MAPPER, tenantId, id).stream().findFirst();
    }

    public Retrocession create(UUID tenantId, UUID contractId, UUID retroContractId,
                               BigDecimal cededPremium, BigDecimal cededLoss, String currency) {
        UUID id = UUID.randomUUID();
        String retrocessionNumber = "RET-" + String.format("%09d",
                jdbc.queryForObject("SELECT nextval('retrocession_number_seq')", Long.class));
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO retrocession (id, tenant_id, business_book, retrocession_number, contract_id,
                                          retro_contract_id, status, ceded_premium, ceded_loss, currency,
                                          version, created_at, updated_at)
                VALUES (?, ?, 'ASSUMED', ?, ?, ?, 'OPEN', ?, ?, ?, 0, ?, ?)
                """,
                id, tenantId, retrocessionNumber, contractId, retroContractId,
                cededPremium, cededLoss, currency,
                Timestamp.from(now), Timestamp.from(now));
        return findById(tenantId, id).orElseThrow();
    }

    public Optional<Retrocession> updateStatus(UUID tenantId, UUID id, RetrocessionStatus status,
                                               int expectedVersion) {
        int updated = jdbc.update("""
                UPDATE retrocession
                   SET status = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                """, status.name(), Timestamp.from(Instant.now()), tenantId, id, expectedVersion);
        if (updated == 0) {
            return Optional.empty();
        }
        return findById(tenantId, id);
    }

    /**
     * Atomically records an FS-RI acknowledgement and moves the case to
     * HANDED_OFF. The lifecycle guard and the optimistic version check are part
     * of the same UPDATE, so a concurrent modification cannot half-apply.
     */
    public Optional<Retrocession> recordHandoff(UUID tenantId, UUID id, int expectedVersion,
                                                String fsriHandoffId, String fsriStatus,
                                                Instant handedOffAt) {
        int updated = jdbc.update("""
                UPDATE retrocession
                   SET status = 'HANDED_OFF',
                       fsri_handoff_id = ?,
                       fsri_status = ?,
                       fsri_handed_off_at = ?,
                       fsri_last_error = NULL,
                       version = version + 1,
                       updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                   AND status IN ('RULE_APPLIED', 'REJECTED')
                """,
                fsriHandoffId, fsriStatus, Timestamp.from(handedOffAt), Timestamp.from(Instant.now()),
                tenantId, id, expectedVersion);
        if (updated == 0) {
            return Optional.empty();
        }
        return findById(tenantId, id);
    }

    /**
     * Records a failed FS-RI attempt without changing the lifecycle status or
     * consuming an optimistic version. Keeping the version stable lets the
     * retry reuse the same transition version and idempotency key, so a
     * provider that processed the request before failing can replay it.
     */
    public Optional<Retrocession> recordHandoffFailure(UUID tenantId, UUID id, int expectedVersion,
                                                       String fsriLastError) {
        int updated = jdbc.update("""
                UPDATE retrocession
                   SET fsri_handoff_id = NULL,
                       fsri_status = 'FAILED',
                       fsri_handed_off_at = NULL,
                       fsri_last_error = ?,
                       updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                   AND status IN ('RULE_APPLIED', 'REJECTED')
                """,
                fsriLastError, Timestamp.from(Instant.now()), tenantId, id, expectedVersion);
        if (updated == 0) {
            return Optional.empty();
        }
        return findById(tenantId, id);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
