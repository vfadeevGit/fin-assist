package ru.stnovator.finassist.service;

import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import ru.stnovator.finassist.entity.*;
import java.time.LocalDate;
import java.util.*;

/** Read-only editor baseline; report-specific schedule selection is deliberately unchanged. */
@Service
public class CorrectionComparisonService {
    public enum Kind {
        SHIPMENT(ShipmentScheduleCorrection.class, ShipmentScheduleCorrectionItem.class,
                ShipmentSchedule.class, ShipmentScheduleItem.class),
        PAYMENT(PaymentScheduleCorrection.class, PaymentScheduleCorrectionItem.class,
                PaymentSchedule.class, PaymentScheduleItem.class);
        public final Class<?> correction, item, schedule, baseItem;
        Kind(Class<?> correction, Class<?> item, Class<?> schedule, Class<?> baseItem) {
            this.correction = correction; this.item = item; this.schedule = schedule; this.baseItem = baseItem;
        }
    }
    private final DataManager data;
    private final RelatedDataAccess access;
    private final SystemAuthenticator authenticator;
    public CorrectionComparisonService(DataManager data, RelatedDataAccess access, SystemAuthenticator authenticator) {
        this.data = data; this.access = access; this.authenticator = authenticator;
    }
    public boolean permitted(Kind kind) {
        return access.canRead(Addendum.class, "contract", "effectiveDate")
                && access.canRead(Project.class, "contract")
                && access.canRead(kind.correction, "project", "addendum", "items")
                && access.canRead(kind.item, "correction", "itemDate", "amount")
                && access.canRead(kind.schedule, "project")
                && access.canRead(kind.baseItem, "schedule", "itemDate", "amount");
    }
    public record Baselines(boolean available, Map<UUID, List<ScheduleComparison.Entry>> schedules) {
        public Baselines { schedules = Map.copyOf(schedules); }
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Baselines load(Kind kind, UUID contract, LocalDate date, UUID current, Set<UUID> projects) {
        if (contract == null || date == null || !permitted(kind)) return new Baselines(false, Map.of());
        if (projects.isEmpty()) return new Baselines(true, Map.of());
        List<?> base = loadBase(kind, contract, projects);
        History history = loadHistory(kind, contract, date, current, projects);
        // Identity-only comparison prevents partially visible histories/items from producing misleading totals.
        Set<Object> visible = identities(base, history);
        Set<Object> complete = authenticator.withSystem(() -> identities(loadBase(kind, contract, projects),
                loadHistory(kind, contract, date, current, projects)));
        if (!visible.equals(complete)
                || !readableReferences(base, "schedule.id", kind.schedule)
                || !readableReferences(base, "schedule.project.id", Project.class)
                || !readableReferences(history.corrections(), "addendum.id", Addendum.class)
                || !readableReferences(history.corrections(), "project.id", Project.class)) {
            return new Baselines(false, Map.of());
        }
        Map<UUID, List<ScheduleComparison.Entry>> result = new HashMap<>();
        for (UUID project : projects) {
            Object previous = history.corrections().stream().filter(c -> project.equals(EntityValues.getValueEx(c, "project.id")))
                    .filter(c -> !Objects.equals(current, EntityValues.getValueEx(c, "addendum.id")))
                    .filter(c -> !history.items(c).isEmpty()).findFirst().orElse(null);
            List<?> source = previous == null ? base.stream()
                    .filter(i -> project.equals(EntityValues.getValueEx(i, "schedule.project.id"))).toList() : history.items(previous);
            result.put(project, entries(source));
        }
        return new Baselines(true, result);
    }
    private boolean readableReferences(List<?> records, String path, Class<?> type) {
        Set<Object> ids = new HashSet<>();
        for (Object record : records) {
            Object id = EntityValues.getValueEx(record, path);
            if (id == null) return false;
            ids.add(id);
        }
        return ids.isEmpty() || data.load(type).query("select e from " + type.getSimpleName() + " e where e.id in :ids")
                .parameter("ids", ids).fetchPlan(FetchPlan.LOCAL).list().size() == ids.size();
    }
    private List<?> loadBase(Kind kind, UUID contract, Set<UUID> projects) {
        return data.load(kind.baseItem).query("select i from " + kind.baseItem.getSimpleName()
                        + " i where i.schedule.project.id in :projects and i.schedule.project.contract.id = :contract")
                .parameter("projects", projects).parameter("contract", contract)
                .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL)
                        .add("schedule", s -> s.add("project", FetchPlan.LOCAL))).list();
    }
    private record History(List<?> corrections, List<?> entries) {
        List<?> items(Object correction) {
            return entries.stream().filter(i -> Objects.equals(EntityValues.getId(correction),
                    EntityValues.getValueEx(i, "correction.id"))).toList();
        }
    }
    private History loadHistory(Kind kind, UUID contract, LocalDate date, UUID current, Set<UUID> projects) {
        List<?> corrections = data.load(kind.correction).query("select c from " + kind.correction.getSimpleName()
                        + " c where c.project.id in :projects and c.addendum.contract.id = :contract"
                        + " and (c.addendum.effectiveDate < :date or c.addendum.id = :current)"
                        + " order by c.addendum.effectiveDate desc")
                .parameter("projects", projects).parameter("contract", contract).parameter("date", date).parameter("current", current)
                .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("project", FetchPlan.LOCAL)
                        .add("addendum", FetchPlan.LOCAL)).list();
        if (corrections.isEmpty()) return new History(corrections, List.of());
        List<?> entries = data.load(kind.item).query("select i from " + kind.item.getSimpleName()
                        + " i where i.correction.id in :ids")
                .parameter("ids", corrections.stream().map(EntityValues::getId).toList())
                .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("correction", FetchPlan.LOCAL)).list();
        return new History(corrections, entries);
    }
    private Set<Object> identities(List<?> base, History history) {
        Set<Object> result = new HashSet<>();
        base.forEach(e -> result.add(EntityValues.getId(e)));
        history.corrections().forEach(c -> result.add(EntityValues.getId(c)));
        history.entries().forEach(i -> result.add(EntityValues.getId(i)));
        return result;
    }
    public static List<?> items(Object correction) {
        List<?> items = EntityValues.getValue(correction, "items");
        return items == null ? List.of() : items;
    }
    public static List<ScheduleComparison.Entry> entries(List<?> items) {
        return items.stream().map(i -> new ScheduleComparison.Entry(EntityValues.getValue(i, "itemDate"),
                EntityValues.getValue(i, "amount"))).toList();
    }
}
