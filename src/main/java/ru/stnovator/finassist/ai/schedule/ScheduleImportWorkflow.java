package ru.stnovator.finassist.ai.schedule;

import io.jmix.core.*;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.accesscontext.InMemoryCrudEntityContext;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.AccessDeniedException;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.service.EffectiveScheduleService;
import ru.stnovator.finassist.service.RelatedDataAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportStore.*;

/** Preparation only: technical draft transitions never write business records. */
@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class ScheduleImportWorkflow {
    public record Choice(UUID id, String caption) {}
    private final ScheduleImportStore store;
    private final ScheduleImportOwnership ownership;
    private final ScheduleNormalizer normalizer;
    private final EffectiveScheduleService schedules;
    private final DataManager data;
    private final RelatedDataAccess access;
    private final SystemAuthenticator auth;
    private final AccessManager accessManager;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final ApplicationContext applicationContext;

    public ScheduleImportWorkflow(ScheduleImportStore store, ScheduleImportOwnership ownership,
                                  ScheduleNormalizer normalizer, EffectiveScheduleService schedules,
                                  DataManager data, RelatedDataAccess access, SystemAuthenticator auth,
                                  AccessManager accessManager, Metadata metadata, MetadataTools metadataTools, ApplicationContext applicationContext) {
        this.store = store; this.ownership = ownership; this.normalizer = normalizer; this.schedules = schedules;
        this.data = data; this.access = access; this.auth = auth; this.accessManager = accessManager;
        this.metadata = metadata; this.metadataTools = metadataTools; this.applicationContext = applicationContext;
    }

    public Draft start(UUID conversationId, Kind kind) {
        Objects.requireNonNull(kind, "kind");
        UUID operation = store.create(conversationId);
        return store.update(operation, 0, new DraftContent(kind, null, null, null, List.of(), null, null, null, null, null, null));
    }

    public Draft setRows(UUID operationId, long revision, List<InputRow> rows, ParseOptions options) {
        Draft draft = current(operationId, revision);
        var normalized = normalizer.normalize(Objects.requireNonNull(rows, "rows"), options);
        if (!normalized.issues().isEmpty() || normalized.rows().isEmpty())
            throw new IllegalArgumentException("Complete valid schedule rows are required: " + normalized.issues());
        DraftContent c = content(draft);
        return store.update(operationId, revision, new DraftContent(c.kind(), c.source(), c.sourceMessageId(), c.mapping(),
                normalized.rows(), c.contractId(), c.projectId(), null, null, null, null));
    }

    public List<Choice> findContracts(UUID operationId, String query) {
        current(operationId, null);
        requireRead(Contract.class, "internalID");
        String search = query == null ? "" : query.strip();
        // Normalize both operands in the database: Java and database case folding can differ.
        // Bound parameters keep prompt text out of JPQL; substring matching has no wildcard semantics.
        return data.load(Contract.class).query("select e from Contract e where locate(lower(:search), lower(e.internalID)) > 0 order by e.internalID, e.id")
                .parameter("search", search).fetchPlan(FetchPlan.LOCAL).maxResults(100).joinTransaction(true).list().stream()
                .map(c -> new Choice(c.getId(), c.getInternalID())).toList();
    }

    public List<Choice> selectContract(UUID operationId, long revision, UUID contractId) {
        Draft draft = current(operationId, revision);
        contract(contractId);
        List<Project> projects = completeProjects(contractId);
        DraftContent c = content(draft);
        store.update(operationId, revision, new DraftContent(c.kind(), c.source(), c.sourceMessageId(), c.mapping(), c.rows(),
                contractId, projects.size() == 1 ? projects.getFirst().getId() : null, null, null, null, null));
        return projects.stream().map(p -> new Choice(p.getId(), p.getName())).toList();
    }

    public Draft selectProject(UUID operationId, long revision, UUID projectId) {
        Draft draft = current(operationId, revision);
        DraftContent c = content(draft);
        contract(c.contractId());
        Project project = project(projectId, c.contractId());
        return store.update(operationId, revision, new DraftContent(c.kind(), c.source(), c.sourceMessageId(), c.mapping(), c.rows(),
                c.contractId(), project.getId(), null, null, null, null));
    }

    public Set<Mode> allowedModes(UUID operationId) {
        DraftContent c = content(current(operationId, null));
        context(c);
        return modes(schedules.load(c.projectId(), c.kind(), null));
    }

    /** Recheck access for the exact draft snapshot the caller is restoring. */
    public Set<Mode> selectionModes(Draft draft) {
        ownership.conversation(draft.conversationId());
        if (draft.status() != Status.COLLECTING && draft.status() != Status.PREVIEW_READY)
            return Set.of();
        DraftContent c = content(draft);
        if (c.contractId() != null) contract(c.contractId());
        Set<Mode> available = Set.of();
        if (c.projectId() != null) {
            context(c);
            available = modes(schedules.load(c.projectId(), c.kind(), null));
        }
        return available;
    }

    /** Complete readable history is needed even when unchanged rows require no new document. */
    public boolean unchanged(Draft draft, Mode mode) {
        ownership.conversation(draft.conversationId());
        var content = content(draft);
        context(content);
        var snapshot = schedules.load(content.projectId(), content.kind(), null);
        if (!modes(snapshot).contains(mode)) throw new IllegalArgumentException("Schedule mode is unavailable");
        var comparison = normalizer.compare(snapshot.rows(), content.rows());
        return comparison.status() == ru.stnovator.finassist.service.ScheduleComparison.Status.COMPLETE
                && comparison.rows().stream().noneMatch(ru.stnovator.finassist.service.ScheduleComparison.Row::changed);
    }

    public ScheduleImportPreview preview(UUID operationId, long revision, Mode mode, String number, LocalDate effectiveDate) {
        Draft draft = current(operationId, revision);
        DraftContent c = content(draft);
        String normalizedNumber = mode == Mode.ADDENDUM && number != null ? number.strip() : null;
        DraftContent requested = new DraftContent(c.kind(), c.source(), c.sourceMessageId(), c.mapping(), c.rows(),
                c.contractId(), c.projectId(), mode, normalizedNumber, mode == Mode.ADDENDUM ? effectiveDate : null, null);
        Prepared prepared = prepare(requested);
        Draft updated = store.update(operationId, revision, requested);
        if (!prepared.noChanges()) updated = store.markPreviewReady(operationId, updated.revision(), prepared.fingerprint());
        return result(updated, prepared);
    }

    /** Restore only an unchanged server preview. Never advances the revision or accepts a new baseline. */
    public ScheduleImportPreview getPreview(UUID operationId, long previewRevision) {
        Draft draft = store.draft(store.lockForCommit(operationId, previewRevision));
        Prepared prepared = prepare(content(draft));
        if (prepared.noChanges() || !Objects.equals(prepared.fingerprint(), draft.content().fingerprint()))
            throw new HistoryChanged();
        return result(draft, prepared);
    }

    static final class HistoryChanged extends ConcurrentModificationException {
        HistoryChanged() { super("Schedule history changed; request a new preview"); }
    }

    static final class HistoryCorrection extends IllegalArgumentException {
        final String prompt;
        HistoryCorrection(String message, String prompt) { super(message); this.prompt = prompt; }
    }

    public record Recovery(ScheduleImportPreview preview, String correctionPrompt) {}

    /** Revalidate the consumed proposal without granting authority to write its replacement. */
    public Recovery refresh(UUID operationId, long revision) {
        var draft = current(operationId, revision);
        var c = content(draft);
        try {
            return new Recovery(preview(operationId, revision, c.mode(), c.addendumNumber(), c.effectiveDate()), null);
        } catch (HistoryCorrection invalid) {
            boolean changeMode = invalid.prompt.startsWith("choose");
            store.update(operationId, revision, new DraftContent(c.kind(), c.source(), c.sourceMessageId(),
                    c.mapping(), c.rows(), c.contractId(), c.projectId(), changeMode ? null : c.mode(), null, null, null));
            return new Recovery(null, invalid.prompt);
        }
    }

    private record Context(Contract contract, Project project) {}
    private record Prepared(Context context, List<Row> rows, ru.stnovator.finassist.service.ScheduleComparison comparison,
                            String fingerprint, boolean noChanges) {}

    private Prepared prepare(DraftContent c) {
        Context context = context(c);
        if (c.rows().isEmpty()) throw new IllegalArgumentException("Schedule rows are required");
        var normalized = normalizer.normalize(c.rows().stream().map(r -> new InputRow(
                r.date() == null ? null : r.date().toString(), r.amount() == null ? null : r.amount().toPlainString(), "draft")).toList(), null);
        if (!normalized.issues().isEmpty()) throw new IllegalArgumentException("Invalid draft rows");
        var snapshot = schedules.load(c.projectId(), c.kind(), null);
        var available = modes(snapshot);
        if (c.mode() == null || !available.contains(c.mode())) {
            var permitted = EnumSet.noneOf(Mode.class);
            for (var choice : available) {
                var alternative = new DraftContent(c.kind(), c.source(), c.sourceMessageId(), c.mapping(), c.rows(),
                        c.contractId(), c.projectId(), choice, c.addendumNumber(), c.effectiveDate(), null);
                try {
                    requireWrites(alternative, context, snapshot, normalized.rows());
                    permitted.add(choice);
                } catch (AccessDeniedException denied) {
                    // Only present choices the user can act on.
                }
            }
            if (permitted.isEmpty()) throw unavailable();
            throw new HistoryCorrection("Schedule mode is unavailable",
                    permitted.size() > 1 ? "chooseReplaceOrAddendum" : "choose" + permitted.iterator().next());
        }
        List<Addendum> addenda = completeAddenda(c.contractId());
        requireWrites(c, context, snapshot, normalized.rows());
        if (c.mode() == Mode.ADDENDUM) {
            if (c.addendumNumber() == null || c.addendumNumber().isBlank() || c.addendumNumber().length() > 50 || c.effectiveDate() == null)
                throw new IllegalArgumentException("Addendum number (up to 50 characters) and effective date are required");
            if (snapshot.latestCorrectionDate() != null && !c.effectiveDate().isAfter(snapshot.latestCorrectionDate()))
                throw new HistoryCorrection("Effective date must follow every correction", "laterDate");
            if (addenda.stream().anyMatch(a -> a.getNumber().equals(c.addendumNumber()) || a.getEffectiveDate().equals(c.effectiveDate())))
                throw new HistoryCorrection("Addendum number or effective date already exists for this contract", "uniqueDocument");
        }
        var comparison = normalizer.compare(snapshot.rows(), normalized.rows());
        boolean unchanged = comparison.rows().stream().noneMatch(ru.stnovator.finassist.service.ScheduleComparison.Row::changed);
        List<String> tokens = new ArrayList<>();
        tokens.add(snapshot.fingerprint());
        tokens.add(context.contract().getInternalID()); tokens.add(context.project().getName());
        tokens.add(c.kind() + ":" + c.mode() + ":" + c.contractId() + ":" + c.projectId());
        tokens.add(c.addendumNumber() + ":" + c.effectiveDate());
        normalized.rows().forEach(r -> tokens.add(r.date() + ":" + r.amount().toPlainString()));
        addenda.forEach(a -> tokens.add(a.getId() + ":" + a.getVersion() + ":" + a.getNumber() + ":" + a.getEffectiveDate()));
        return new Prepared(context, normalized.rows(), comparison, fingerprint(tokens), unchanged);
    }

    private ScheduleImportPreview result(Draft draft, Prepared p) {
        var c = draft.content();
        return new ScheduleImportPreview(draft.operationId(), draft.revision(), p.context().contract().getInternalID(),
                p.context().project().getName(), c.kind(), c.mode(), c.addendumNumber(), c.effectiveDate(), p.rows(),
                p.comparison(), p.comparison().afterTotal(), p.fingerprint(), p.noChanges());
    }

    private Draft current(UUID operationId, Long revision) {
        Draft draft = store.load(operationId);
        ownership.conversation(draft.conversationId());
        if (revision != null && draft.revision() != revision) throw new ConcurrentModificationException("Draft revision has changed");
        if (draft.status() != Status.COLLECTING && draft.status() != Status.PREVIEW_READY)
            throw new IllegalStateException("Import is closed or expired");
        return draft;
    }
    private DraftContent content(Draft draft) {
        if (draft.content() == null || draft.content().kind() == null) throw new IllegalStateException("Import kind is required");
        return draft.content();
    }
    private Context context(DraftContent c) { return new Context(contract(c.contractId()), project(c.projectId(), c.contractId())); }
    private Contract contract(UUID id) {
        if (id == null) throw new IllegalStateException("Select a contract");
        requireRead(Contract.class, "internalID");
        return data.load(Contract.class).id(id).fetchPlan(FetchPlan.LOCAL).joinTransaction(true).optional().orElseThrow(ScheduleImportWorkflow::unavailable);
    }
    private Project project(UUID id, UUID contractId) {
        if (id == null) throw new IllegalStateException("Select another contract or choose a project");
        requireRead(Project.class, "name", "contract");
        Project project = data.load(Project.class).id(id).fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("contract", FetchPlan.LOCAL))
                .joinTransaction(true).optional().orElseThrow(ScheduleImportWorkflow::unavailable);
        if (project.getContract() == null || !project.getContract().getId().equals(contractId)) throw unavailable();
        return project;
    }
    private List<Project> completeProjects(UUID contractId) {
        requireRead(Project.class, "name", "contract");
        List<Project> projects = projects(contractId);
        Set<UUID> complete = auth.withSystem(() -> ids(projects(contractId)));
        if (!ids(projects).equals(complete)) throw unavailable();
        return projects;
    }
    private List<Project> projects(UUID contractId) {
        return data.load(Project.class).query("select e from Project e where e.contract.id = :contract order by e.name, e.id")
                .parameter("contract", contractId).fetchPlan(FetchPlan.LOCAL).joinTransaction(true).list();
    }
    private List<Addendum> completeAddenda(UUID contractId) {
        requireRead(Addendum.class, "contract", "number", "effectiveDate");
        List<Addendum> addenda = addenda(contractId);
        Set<UUID> complete = auth.withSystem(() -> ids(addenda(contractId)));
        if (!ids(addenda).equals(complete)) throw unavailable();
        return addenda;
    }
    private List<Addendum> addenda(UUID contractId) {
        return data.load(Addendum.class).query("select e from Addendum e where e.contract.id = :contract")
                .parameter("contract", contractId).fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("contract", FetchPlan.LOCAL))
                .joinTransaction(true).list();
    }
    private static Set<UUID> ids(List<?> entities) { return entities.stream().map(e -> (UUID) EntityValues.getId(e)).collect(Collectors.toSet()); }
    private void requireRead(Class<?> type, String... attributes) { if (!access.canRead(type, attributes)) throw unavailable(); }
    private static Set<Mode> modes(EffectiveScheduleService.Snapshot s) {
        if (s.hasCorrections()) return Set.of(Mode.ADDENDUM);
        if (s.baseScheduleId() == null) return Set.of(Mode.CREATE);
        return s.rows().isEmpty() ? Set.of(Mode.FILL) : Set.of(Mode.REPLACE, Mode.ADDENDUM);
    }

    private void requireWrites(DraftContent c, Context context, EffectiveScheduleService.Snapshot snapshot, List<Row> rows) {
        boolean payment = c.kind() == Kind.PAYMENT;
        Class<?> scheduleType = payment ? PaymentSchedule.class : ShipmentSchedule.class;
        Class<?> itemType = payment ? PaymentScheduleItem.class : ShipmentScheduleItem.class;
        Class<?> correctionType = payment ? PaymentScheduleCorrection.class : ShipmentScheduleCorrection.class;
        Class<?> correctionItemType = payment ? PaymentScheduleCorrectionItem.class : ShipmentScheduleCorrectionItem.class;
        // All edited references belong to copies. A predicate must see the complete proposed graph,
        // and building that graph must never dirty entities joined to the preview transaction.
        Project proposedProject = metadataTools.deepCopy(context.project());
        proposedProject.setContract(metadataTools.deepCopy(context.contract()));
        Object header;
        String parent;
        Addendum addendum = null;
        List<Object> removedItems = new ArrayList<>();
        if (c.mode() == Mode.ADDENDUM) {
            addendum = data.create(Addendum.class);
            addendum.setContract(proposedProject.getContract()); addendum.setNumber(c.addendumNumber()); addendum.setEffectiveDate(c.effectiveDate());
            header = data.create(correctionType);
            EntityValues.setValue(header, "project", proposedProject); EntityValues.setValue(header, "addendum", addendum);
            addendum.setPaymentScheduleCorrections(payment ? List.of((PaymentScheduleCorrection) header) : List.of());
            addendum.setShipmentScheduleCorrections(payment ? List.of() : List.of((ShipmentScheduleCorrection) header));
            itemType = correctionItemType; parent = "correction";
        } else {
            Object existing = c.mode() == Mode.CREATE ? null : data.load(scheduleType).id(snapshot.baseScheduleId())
                    .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("project", FetchPlan.LOCAL)).joinTransaction(true).one();
            header = baseGraph(scheduleType, existing, proposedProject, payment);
            parent = "schedule";
            if (c.mode() == Mode.REPLACE) {
                Object previous = baseGraph(scheduleType, existing, metadataTools.deepCopy(context.project()), payment);
                for (Object item : data.load(itemType).query("select e from " + itemType.getSimpleName() + " e where e.schedule.id = :schedule order by e.itemDate, e.id")
                        .parameter("schedule", snapshot.baseScheduleId()).fetchPlan(FetchPlan.LOCAL).joinTransaction(true).list()) {
                    Object copy = metadataTools.deepCopy(item);
                    EntityValues.setValue(copy, "schedule", previous); removedItems.add(copy);
                }
                EntityValues.setValue(previous, "items", removedItems);
            }
        }
        List<Object> proposedItems = new ArrayList<>();
        for (Row row : rows) {
            Object item = data.create(itemType); EntityValues.setValue(item, parent, header);
            EntityValues.setValue(item, "itemDate", row.date()); EntityValues.setValue(item, "amount", row.amount());
            proposedItems.add(item);
        }
        EntityValues.setValue(header, "items", proposedItems);

        // Evaluate every predicate only after both sides of every operation composition are linked.
        if (addendum != null) {
            write(Addendum.class, addendum, Action.CREATE, "contract", "number", "effectiveDate");
            write(correctionType, header, Action.CREATE, "project", "addendum");
        } else {
            write(scheduleType, header, c.mode() == Mode.CREATE ? Action.CREATE : Action.UPDATE, "project");
        }
        for (Object item : removedItems) write(itemType, item, Action.DELETE);
        for (Object item : proposedItems) write(itemType, item, Action.CREATE, parent, "itemDate", "amount");
    }

    private Object baseGraph(Class<?> scheduleType, Object existing, Project project, boolean payment) {
        Object header = existing == null ? data.create(scheduleType) : metadataTools.deepCopy(existing);
        EntityValues.setValue(header, "project", project);
        if (payment) project.setPaymentSchedule((PaymentSchedule) header);
        else project.setShipmentSchedule((ShipmentSchedule) header);
        return header;
    }
    private enum Action { CREATE, UPDATE, DELETE }
    private void write(Class<?> type, Object entity, Action action, String... attributes) {
        var crud = new CrudEntityContext(metadata.getClass(type)); accessManager.applyRegisteredConstraints(crud);
        boolean permitted = switch (action) {
            case CREATE -> crud.isCreatePermitted(); case UPDATE -> crud.isUpdatePermitted(); case DELETE -> crud.isDeletePermitted();
        };
        if (!permitted) throw unavailable();
        for (String attribute : attributes) {
            var attributeContext = new EntityAttributeContext(metadata.getClass(type), attribute);
            accessManager.applyRegisteredConstraints(attributeContext);
            if (!attributeContext.canModify()) throw unavailable();
        }
        var memory = new InMemoryCrudEntityContext(metadata.getClass(type), applicationContext);
        accessManager.applyRegisteredConstraints(memory);
        boolean rowPermitted = switch (action) {
            case CREATE -> memory.isCreatePermitted(entity); case UPDATE -> memory.isUpdatePermitted(entity); case DELETE -> memory.isDeletePermitted(entity);
        };
        if (!rowPermitted) throw unavailable();
    }
    private static String fingerprint(List<String> tokens) {
        Collections.sort(tokens);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n", tokens).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private static AccessDeniedException unavailable() { return new AccessDeniedException("scheduleImport", "Complete schedule import context is unavailable", "prepare"); }
}
