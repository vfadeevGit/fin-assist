package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.FluentLoader;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.AccessDeniedException;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.Kind;
import ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.Row;
import ru.stnovator.finassist.entity.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Resolves complete, caller-readable schedule snapshots independently for each kind. */
@Service
public class EffectiveScheduleService {
    private final DataManager data;
    private final RelatedDataAccess access;
    private final SystemAuthenticator authenticator;

    public EffectiveScheduleService(DataManager data, RelatedDataAccess access, SystemAuthenticator authenticator) {
        this.data = data;
        this.access = access;
        this.authenticator = authenticator;
    }

    public record Key(UUID projectId, Kind kind) {}
    public record Snapshot(UUID projectId, Kind kind, UUID baseScheduleId, UUID sourceAddendumId,
                           List<Row> rows, boolean hasCorrections, LocalDate latestCorrectionDate,
                           String fingerprint) {
        public Snapshot { rows = List.copyOf(rows); }
    }

    // REQUIRED intentionally joins the caller's transaction (including the final import commit).
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot load(UUID projectId, Kind kind, LocalDate asOfInclusive) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(kind, "kind");
        return resolve(Set.of(projectId), Set.of(kind), asOfInclusive).get(new Key(projectId, kind));
    }

    /** Null cutoff includes all recorded history, including future and empty corrections. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<Key, Snapshot> loadAll(Set<UUID> projectIds, LocalDate asOfInclusive) {
        return resolve(Set.copyOf(projectIds), EnumSet.allOf(Kind.class), asOfInclusive);
    }

    private Map<Key, Snapshot> resolve(Set<UUID> projects, Set<Kind> kinds, LocalDate cutoff) {
        if (projects.isEmpty()) return Map.of();
        Map<Key, Snapshot> result = new HashMap<>();
        for (Kind kind : kinds) {
            Types types = Types.of(kind);
            requirePermissions(types);
            History history = history(projects, types, cutoff);
            // Privileged data never leaves this block: only entity identities are compared.
            Set<String> complete = authenticator.withSystem(() -> history(projects, types, cutoff).identities());
            if (!history.identities().equals(complete) || history.projects.size() != projects.size()) throw unavailable();
            Map<UUID, Object> contracts = byId(history.contracts);
            Map<UUID, Object> addenda = byId(history.addenda);
            Map<UUID, List<Object>> schedules = group(history.schedules, "project.id");
            Map<UUID, List<Object>> baseItems = group(history.baseItems, "schedule.id");
            Map<UUID, List<Object>> corrections = group(history.corrections, "project.id");
            Map<UUID, List<Object>> items = group(history.items, "correction.id");
            for (Object project : history.projects) {
                UUID projectId = id(project);
                Object contract = contracts.get(reference(project, "contract.id"));
                if (contract == null) throw unavailable();
                List<Object> headers = schedules.getOrDefault(projectId, List.of());
                if (headers.size() > 1) throw unavailable();
                Object base = headers.isEmpty() ? null : headers.getFirst();
                List<Object> baseRows = base == null ? List.of() : baseItems.getOrDefault(id(base), List.of());
                List<Object> changes = corrections.getOrDefault(projectId, List.of());
                List<Object> sorted = new ArrayList<>(changes);
                sorted.sort(Comparator.comparing((Object c) -> effectiveDate(c, addenda)).reversed()
                        .thenComparing(EffectiveScheduleService::id));
                Object selected = sorted.stream().filter(c -> !items.getOrDefault(id(c), List.of()).isEmpty())
                        .findFirst().orElse(null);
                List<Object> selectedRows = selected == null ? baseRows : items.get(id(selected));
                List<String> fingerprint = new ArrayList<>();
                fingerprint.add(kind + ":" + cutoff);
                fingerprint.add(token("project", project, "contract.id"));
                fingerprint.add(token("contract", contract));
                if (base != null) fingerprint.add(token("schedule", base, "project.id"));
                baseRows.forEach(i -> fingerprint.add(token("baseItem", i, "schedule.id", "itemDate", "amount")));
                for (Object change : changes) {
                    Object addendum = addenda.get(reference(change, "addendum.id"));
                    if (addendum == null || !id(contract).equals(reference(addendum, "contract.id"))) throw unavailable();
                    fingerprint.add(token("correction", change, "project.id", "addendum.id"));
                    fingerprint.add(token("addendum", addendum, "contract.id", "effectiveDate"));
                    items.getOrDefault(id(change), List.of()).forEach(i ->
                            fingerprint.add(token("correctionItem", i, "correction.id", "itemDate", "amount")));
                }
                Snapshot snapshot = new Snapshot(projectId, kind, base == null ? null : id(base),
                        selected == null ? null : reference(selected, "addendum.id"), rows(selectedRows),
                        !changes.isEmpty(), sorted.isEmpty() ? null : effectiveDate(sorted.getFirst(), addenda),
                        fingerprint(fingerprint));
                result.put(new Key(projectId, kind), snapshot);
            }
        }
        return Map.copyOf(result);
    }

    private void requirePermissions(Types types) {
        if (!access.canRead(Project.class, "contract") || !access.canRead(Contract.class)
                || !access.canRead(Addendum.class, "contract", "effectiveDate")
                || !access.canRead(types.schedule, "project")
                || !access.canRead(types.baseItem, "schedule", "itemDate", "amount")
                || !access.canRead(types.correction, "project", "addendum")
                || !access.canRead(types.item, "correction", "itemDate", "amount")) throw unavailable();
    }

    private History history(Set<UUID> projects, Types types, LocalDate cutoff) {
        List<?> projectRows = query(Project.class, "e.id in :projects", projects)
                .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("contract", FetchPlan.LOCAL)).list();
        List<?> contracts = query(Contract.class,
                "e.id in (select p.contract.id from Project p where p.id in :projects)", projects)
                .fetchPlan(FetchPlan.LOCAL).list();
        List<?> schedules = query(types.schedule, "e.project.id in :projects", projects)
                .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("project", FetchPlan.LOCAL)).list();
        List<?> baseItems = query(types.baseItem, "e.schedule.project.id in :projects", projects)
                .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("schedule", FetchPlan.LOCAL)).list();
        String boundary = cutoff == null ? "" : " and e.addendum.effectiveDate <= :cutoff";
        var correctionQuery = query(types.correction, "e.project.id in :projects" + boundary, projects);
        if (cutoff != null) correctionQuery.parameter("cutoff", cutoff);
        List<?> corrections = correctionQuery.fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL)
                .add("project", FetchPlan.LOCAL).add("addendum", FetchPlan.LOCAL)).list();
        // Query children independently: joined references do not enforce policies on their parents.
        String itemBoundary = cutoff == null ? "" : " and e.correction.addendum.effectiveDate <= :cutoff";
        var itemQuery = query(types.item, "e.correction.project.id in :projects" + itemBoundary, projects);
        if (cutoff != null) itemQuery.parameter("cutoff", cutoff);
        List<?> items = itemQuery.fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("correction", FetchPlan.LOCAL)).list();
        var addendumQuery = query(Addendum.class, "e.id in (select c.addendum.id from "
                + types.correction.getSimpleName() + " c where c.project.id in :projects)"
                + (cutoff == null ? "" : " and e.effectiveDate <= :cutoff"), projects);
        if (cutoff != null) addendumQuery.parameter("cutoff", cutoff);
        List<?> addenda = addendumQuery.fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL)
                .add("contract", FetchPlan.LOCAL)).list();
        return new History(projectRows, contracts, schedules, baseItems, corrections, items, addenda);
    }

    private FluentLoader.ByQuery<?> query(Class<?> type, String condition, Set<UUID> projects) {
        return data.load(type).query("select e from " + type.getSimpleName() + " e where " + condition)
                .parameter("projects", projects).joinTransaction(true);
    }

    private record Types(Class<?> schedule, Class<?> baseItem, Class<?> correction, Class<?> item) {
        static Types of(Kind kind) {
            return kind == Kind.PAYMENT
                    ? new Types(PaymentSchedule.class, PaymentScheduleItem.class,
                    PaymentScheduleCorrection.class, PaymentScheduleCorrectionItem.class)
                    : new Types(ShipmentSchedule.class, ShipmentScheduleItem.class,
                    ShipmentScheduleCorrection.class, ShipmentScheduleCorrectionItem.class);
        }
    }

    private record History(List<?> projects, List<?> contracts, List<?> schedules, List<?> baseItems,
                           List<?> corrections, List<?> items, List<?> addenda) {
        Set<String> identities() {
            Set<String> result = new HashSet<>();
            List<List<?>> groups = List.of(projects, contracts, schedules, baseItems, corrections, items, addenda);
            for (int i = 0; i < groups.size(); i++) {
                int category = i;
                groups.get(i).forEach(e -> result.add(category + ":" + id(e)));
            }
            return result;
        }
    }

    private static Map<UUID, Object> byId(List<?> records) {
        return records.stream().collect(Collectors.toMap(EffectiveScheduleService::id, e -> e));
    }

    private static Map<UUID, List<Object>> group(List<?> records, String path) {
        return records.stream().collect(Collectors.groupingBy(e -> reference(e, path)));
    }

    private static UUID id(Object entity) { return (UUID) EntityValues.getId(entity); }

    private static UUID reference(Object entity, String path) {
        UUID id = EntityValues.getValueEx(entity, path);
        if (id == null) throw unavailable();
        return id;
    }

    private static LocalDate effectiveDate(Object correction, Map<UUID, Object> addenda) {
        Object addendum = addenda.get(reference(correction, "addendum.id"));
        if (addendum == null) throw unavailable();
        LocalDate date = EntityValues.getValue(addendum, "effectiveDate");
        if (date == null) throw unavailable();
        return date;
    }

    private static List<Row> rows(List<?> items) {
        Map<LocalDate, BigDecimal> amounts = new TreeMap<>();
        for (Object item : items) amounts.merge(EntityValues.getValue(item, "itemDate"),
                Objects.requireNonNull(EntityValues.<BigDecimal>getValue(item, "amount")), BigDecimal::add);
        return amounts.entrySet().stream().map(e -> new Row(e.getKey(), e.getValue())).toList();
    }

    private static String token(String category, Object entity, String... attributes) {
        StringJoiner token = new StringJoiner("|").add(category).add(id(entity).toString())
                .add(String.valueOf((Object) EntityValues.getValue(entity, "version")));
        for (String attribute : attributes) token.add(String.valueOf((Object) EntityValues.getValueEx(entity, attribute)));
        return token.toString();
    }

    private static String fingerprint(List<String> tokens) {
        Collections.sort(tokens);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", tokens).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static AccessDeniedException unavailable() {
        return new AccessDeniedException("scheduleHistory", "Schedule history unavailable", "read");
    }
}
