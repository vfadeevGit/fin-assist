package ru.stnovator.finassist.ai.schedule;

import io.jmix.core.*;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.service.ScheduleMutationGuard;

import java.util.*;

import static ru.stnovator.finassist.ai.schedule.ScheduleImportStore.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

/** Server UI confirmation boundary. Never register this operation as an AI tool or generic endpoint. */
@Service
public class ScheduleImportCommitService {
    public record CommitResult(UUID operationId, String entityName, UUID entityId, boolean alreadySaved) {}

    private final ScheduleImportStore store;
    private final ScheduleImportWorkflow workflow;
    private final ScheduleMutationGuard guard;
    private final DataManager data;
    private final UnconstrainedDataManager internal;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final EntityStates entityStates;

    public ScheduleImportCommitService(ScheduleImportStore store, ScheduleImportWorkflow workflow,
                                       ScheduleMutationGuard guard, DataManager data,
                                       UnconstrainedDataManager internal, Metadata metadata, MetadataTools metadataTools, EntityStates entityStates) {
        this.store = store; this.workflow = workflow; this.guard = guard;
        this.data = data; this.internal = internal; this.metadata = metadata; this.metadataTools = metadataTools; this.entityStates = entityStates;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommitResult confirm(UUID operationId, long previewRevision) {
        // Operation ownership and its persisted conversation are checked by Store under this lock.
        Draft draft = store.load(operationId);
        if (draft.status() == Status.SAVED) {
            if (previewRevision != draft.revision() - 1)
                throw new ConcurrentModificationException("Confirmed preview revision does not match");
            Class<?> resultType = resultType(draft.content());
            if (draft.resultId() == null || data.load(resultType).id(draft.resultId()).joinTransaction(true).optional().isEmpty())
                throw new AccessDeniedException("scheduleImport", "Saved result is unavailable", "confirm");
            return new CommitResult(operationId, metadata.getClass(resultType).getName(), draft.resultId(), true);
        }
        AiScheduleImport receipt = store.lockForCommit(operationId, previewRevision);
        DraftContent content = store.draft(receipt).content();
        // This order matches ordinary mutation serialization. Revalidation must read AFTER the lock.
        guard.lockContracts(Set.of(content.contractId()));
        ScheduleImportPreview preview = workflow.getPreview(operationId, previewRevision);
        Object result = saveComposition(content, preview);
        UUID resultId = (UUID) EntityValues.getId(result);
        receipt.setResultId(resultId);
        ScheduleImportStore.finish(receipt, Status.SAVED);
        // Only the technical receipt uses internal persistence; it joins the domain transaction.
        internal.saveWithoutReload(receipt);
        return new CommitResult(operationId, metadata.getClass(resultType(content)).getName(), resultId, false);
    }

    private Object saveComposition(DraftContent c, ScheduleImportPreview preview) {
        boolean payment = c.kind() == Kind.PAYMENT;
        Class<?> scheduleType = payment ? PaymentSchedule.class : ShipmentSchedule.class;
        Class<?> itemType = payment ? PaymentScheduleItem.class : ShipmentScheduleItem.class;
        Class<?> correctionType = payment ? PaymentScheduleCorrection.class : ShipmentScheduleCorrection.class;
        // Link isolated copies so predicates see the complete graph without dirtying Contract or Project.
        Project project = metadataTools.deepCopy(data.load(Project.class).id(c.projectId())
                .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("contract", FetchPlan.LOCAL)).joinTransaction(true).one());
        SaveContext save = new SaveContext().setJoinTransaction(true);
        List<Object> removedItems = new ArrayList<>();
        Object header;
        Object result;
        String parent;
        if (c.mode() == Mode.ADDENDUM) {
            Addendum addendum = data.create(Addendum.class);
            addendum.setContract(project.getContract()); addendum.setNumber(preview.number()); addendum.setEffectiveDate(preview.effectiveDate());
            header = data.create(correctionType);
            EntityValues.setValue(header, "project", project); EntityValues.setValue(header, "addendum", addendum);
            addendum.setPaymentScheduleCorrections(payment ? List.of((PaymentScheduleCorrection) header) : List.of());
            addendum.setShipmentScheduleCorrections(payment ? List.of() : List.of((ShipmentScheduleCorrection) header));
            save.saving(addendum); result = addendum; parent = "correction";
            itemType = payment ? PaymentScheduleCorrectionItem.class : ShipmentScheduleCorrectionItem.class;
        } else {
            Object existing = c.mode() == Mode.CREATE ? null : data.load(scheduleType)
                    .query("select e from " + scheduleType.getSimpleName() + " e where e.project.id = :project")
                    .parameter("project", c.projectId()).fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("project", FetchPlan.LOCAL))
                    .joinTransaction(true).one();
            header = baseGraph(scheduleType, existing, project, payment);
            if (c.mode() == Mode.REPLACE) {
                Object previous = baseGraph(scheduleType, existing, metadataTools.deepCopy(project), payment);
                List<Object> oldItems = new ArrayList<>();
                for (Object old : data.load(itemType).query("select e from " + itemType.getSimpleName() + " e where e.schedule.id = :schedule")
                        .parameter("schedule", EntityValues.getId(existing)).fetchPlan(FetchPlan.LOCAL).joinTransaction(true).list()) {
                    Object copy = metadataTools.deepCopy(old); entityStates.makeDetached(copy);
                    EntityValues.setValue(copy, "schedule", previous);
                    oldItems.add(copy); removedItems.add(copy);
                }
                EntityValues.setValue(previous, "items", oldItems);
            }
            result = header; parent = "schedule";
        }
        List<Object> items = new ArrayList<>();
        for (Row row : preview.rows()) {
            Object item = data.create(itemType); EntityValues.setValue(item, parent, header);
            EntityValues.setValue(item, "itemDate", row.date()); EntityValues.setValue(item, "amount", row.amount());
            items.add(item); save.saving(item);
        }
        EntityValues.setValue(header, "items", items); save.saving(header);
        // Jmix saves before deleting within one SaveContext. Remove first so DELETE predicates see
        // the original snapshot; the outer transaction rolls this back if any later write fails.
        if (!removedItems.isEmpty()) data.save(new SaveContext().removing(removedItems)
                .setJoinTransaction(true).setDiscardSaved(true));
        // Secured DataManager checks the complete proposed graph again.
        EntitySet saved = data.save(save);
        return saved.get(result);
    }

    private Object baseGraph(Class<?> type, Object existing, Project project, boolean payment) {
        Object header = existing == null ? data.create(type) : metadataTools.deepCopy(existing);
        // MetadataTools copies values into a NEW instance; existing headers must retain update semantics.
        if (existing != null) entityStates.makeDetached(header);
        EntityValues.setValue(header, "project", project);
        if (payment) project.setPaymentSchedule((PaymentSchedule) header);
        else project.setShipmentSchedule((ShipmentSchedule) header);
        return header;
    }

    static Class<?> resultType(DraftContent c) {
        if (c.mode() == Mode.ADDENDUM) return Addendum.class;
        return c.kind() == Kind.PAYMENT ? PaymentSchedule.class : ShipmentSchedule.class;
    }
}
