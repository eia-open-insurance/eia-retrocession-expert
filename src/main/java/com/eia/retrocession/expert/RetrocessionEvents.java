package com.eia.retrocession.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventIds;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event factory for the Retrocession vertical. Event names are the Retrocession
 * slice names; they differ from the planned catalog names (see the rollout
 * report).
 *
 * <p>Retrocession is an ASSUMED-only vertical, so every event carries
 * {@code book=ASSUMED}. Rows start at version 0 for API compatibility, while the
 * canonical envelope requires {@code aggregateVersion >= 1}; the event version
 * is therefore the row version plus one (create -> 1, first update -> 2).</p>
 */
final class RetrocessionEvents {
    static final String CREATED = "retrocession.retrocession.created";
    static final String UPDATED = "retrocession.retrocession.updated";
    static final String HANDED_OFF = "retrocession.retrocession.handed-off";

    private static final Book EVENT_BOOK = Book.ASSUMED;
    private static final String VERTICAL = "retrocession";
    private static final String AGGREGATE_TYPE = "Retrocession";
    private static final String SOURCE_BUILD = "eia-retrocession-expert@0.1.0-SNAPSHOT";

    private RetrocessionEvents() {
    }

    static EventEnvelope created(Retrocession retrocession, Instant now) {
        Map<String, Object> data = identity(retrocession);
        data.put("status", retrocession.status().name());
        data.put("cededPremium", money(retrocession.cededPremium()));
        data.put("cededLoss", money(retrocession.cededLoss()));
        data.put("currency", retrocession.currency());
        data.put("version", retrocession.version());

        return envelope(retrocession, CREATED, retrocession.version() + 1L, now, data);
    }

    static EventEnvelope updated(Retrocession before, Retrocession after, Instant now) {
        Map<String, Object> data = identity(after);
        data.put("changedFields", List.of("status"));
        data.put("version", after.version());
        data.put("from", before.status().name());
        data.put("to", after.status().name());

        return envelope(after, UPDATED, after.version() + 1L, now, data);
    }

    static EventEnvelope handedOff(Retrocession before, Retrocession after, Instant now) {
        Map<String, Object> data = identity(after);
        data.put("version", after.version());
        data.put("from", before.status().name());
        data.put("to", after.status().name());
        data.put("status", after.status().name());
        data.put("fsriHandoffId", after.fsriHandoffId());
        data.put("fsriStatus", after.fsriStatus());
        if (after.fsriHandedOffAt() != null) {
            data.put("fsriHandedOffAt", after.fsriHandedOffAt().toString());
        }

        return envelope(after, HANDED_OFF, after.version() + 1L, now, data);
    }

    private static Map<String, Object> identity(Retrocession retrocession) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("retrocessionId", retrocession.id());
        data.put("retrocessionNumber", retrocession.retrocessionNumber());
        data.put("businessBook", retrocession.businessBook());
        data.put("contractId", retrocession.contractId());
        data.put("retroContractId", retrocession.retroContractId());
        return data;
    }

    private static String money(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    private static EventEnvelope envelope(
            Retrocession retrocession, String eventType, long aggregateVersion, Instant now,
            Map<String, Object> data) {
        UUID eventId = EventIds.deterministic(
                retrocession.tenantId(), AGGREGATE_TYPE, retrocession.id(), aggregateVersion, eventType);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(retrocession.tenantId())
                .book(EVENT_BOOK)
                .vertical(VERTICAL)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(retrocession.id())
                .aggregateVersion(aggregateVersion)
                .sequence(0)
                .actor(EventActor.system("eia-retrocession-expert"))
                .subject(EventSubject.of(VERTICAL, "retrocession", retrocession.id(),
                        retrocession.retrocessionNumber()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }
}
