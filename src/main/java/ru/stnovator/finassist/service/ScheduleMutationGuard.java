package ru.stnovator.finassist.service;

import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.event.EntityChangedEvent;
import io.jmix.data.PersistenceHints;
import jakarta.persistence.LockModeType;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.stnovator.finassist.entity.*;

import java.util.*;

/**
 * Serializes domain commits with confirmation's locked history read.
 * Confirmation must lock its owned operation first, then contracts, and re-read at READ_COMMITTED.
 * Jmix changed events run after flush but before commit; a conflicting flush can deadlock and roll back.
 * Direct SQL and saves explicitly suppressing Jmix events are outside this protocol.
 */
@Service
public class ScheduleMutationGuard {
    private static final Map<Class<?>, List<String>> CONTRACT_PATHS = Map.ofEntries(
            Map.entry(Project.class, List.of("contract.id")),
            Map.entry(Addendum.class, List.of("contract.id")),
            Map.entry(PaymentSchedule.class, List.of("project.contract.id")),
            Map.entry(ShipmentSchedule.class, List.of("project.contract.id")),
            Map.entry(PaymentScheduleItem.class, List.of("schedule.project.contract.id")),
            Map.entry(ShipmentScheduleItem.class, List.of("schedule.project.contract.id")),
            Map.entry(PaymentScheduleCorrection.class, List.of("project.contract.id", "addendum.contract.id")),
            Map.entry(ShipmentScheduleCorrection.class, List.of("project.contract.id", "addendum.contract.id")),
            Map.entry(PaymentScheduleCorrectionItem.class,
                    List.of("correction.project.contract.id", "correction.addendum.contract.id")),
            Map.entry(ShipmentScheduleCorrectionItem.class,
                    List.of("correction.project.contract.id", "correction.addendum.contract.id")));

    private final UnconstrainedDataManager data;

    public ScheduleMutationGuard(UnconstrainedDataManager data) { this.data = data; }

    /** Requires the caller's transaction; never starts a transaction whose locks would immediately expire. */
    public void lockContracts(Set<UUID> contractIds) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Contract locks require an active transaction");
        }
        // Only structural identifiers are read with elevated access. No domain writes or data escape this service.
        contractIds.stream().sorted().forEach(id -> data.load(Contract.class).id(id)
                .fetchPlan(FetchPlan.LOCAL).hint(PersistenceHints.SOFT_DELETION, false)
                .lockMode(LockModeType.PESSIMISTIC_WRITE).optional());
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void changed(EntityChangedEvent<?> event) {
        List<String> paths = CONTRACT_PATHS.get(event.getEntityId().getEntityClass());
        if (paths == null) return;
        Set<UUID> locked = new HashSet<>();
        while (true) {
            Set<UUID> required = new HashSet<>();
            if (event.getType() != EntityChangedEvent.Type.DELETED) {
                collect(event.getEntityId(), required);
            }
            // Old parent references cover moves and deletions, including hard deletes whose row is gone.
            paths.stream().map(path -> path.substring(0, path.indexOf('.'))).distinct().forEach(reference -> {
                Id<?> old = event.getChanges().getOldReferenceId(reference);
                if (old != null) collect(old, required);
            });
            required.removeAll(locked);
            if (required.isEmpty()) return;
            lockContracts(required);
            locked.addAll(required);
            // A parent could have moved while we waited. Re-resolve after acquiring locks so the
            // mutation also participates in its new contract. Concurrent moves must lock the old one.
        }
    }

    private void collect(Id<?> entity, Set<UUID> contracts) {
        if (entity.getEntityClass() == Contract.class) {
            contracts.add((UUID) entity.getValue());
            return;
        }
        for (String path : CONTRACT_PATHS.getOrDefault(entity.getEntityClass(), List.of())) {
            data.loadValue("select e." + path + " from " + entity.getEntityClass().getSimpleName()
                            + " e where e.id = :id", UUID.class)
                    .parameter("id", entity.getValue()).hint(PersistenceHints.SOFT_DELETION, false)
                    .optional().ifPresent(contracts::add);
        }
        // A hard-deleted ancestor is covered by its own changed event and its old reference ids.
    }
}
