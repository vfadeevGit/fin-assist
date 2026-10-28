package ru.stnovator.finassist.service;

import io.jmix.core.*;
import io.jmix.security.constraint.PolicyStore;
import org.springframework.stereotype.Service;
import ru.stnovator.finassist.entity.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.IntFunction;

/** Base schedules only. Scalar aggregates are used only when item row policies are absent. */
@Service
public class ProjectScheduleService {
    private static final int BATCH_SIZE = 128;
    public record Row(LocalDate date, BigDecimal shipments, BigDecimal payments) {}
    public record SchedulePage(List<Row> rows, boolean hasNext, BigDecimal shipmentTotal, BigDecimal paymentTotal,
                               boolean shipmentsAvailable, boolean paymentsAvailable) {
        public SchedulePage { rows = List.copyOf(rows); }
    }
    private record Amount(LocalDate date, BigDecimal amount) {}
    private record Side(Iterator<Amount> amounts, BigDecimal total, boolean available) {}
    private final DataManager data;
    private final RelatedDataAccess access;
    private final PolicyStore policies;
    private final Metadata metadata;

    public ProjectScheduleService(DataManager data, RelatedDataAccess access, PolicyStore policies, Metadata metadata) {
        this.data = data; this.access = access; this.policies = policies; this.metadata = metadata;
    }

    public boolean canReadShipments() {
        return access.canRead(Project.class, "shipmentSchedule") && access.canRead(ShipmentSchedule.class, "project")
                && access.canRead(ShipmentScheduleItem.class, "schedule", "itemDate", "amount");
    }
    public boolean canReadPayments() {
        return access.canRead(Project.class, "paymentSchedule") && access.canRead(PaymentSchedule.class, "project")
                && access.canRead(PaymentScheduleItem.class, "schedule", "itemDate", "amount");
    }

    public SchedulePage schedule(UUID projectId, LocalDate afterDate) {
        access.requireSource(Project.class, projectId);
        Side shipments = side(projectId, true, afterDate);
        Side payments = side(projectId, false, afterDate);
        Iterator<Amount> si = shipments.amounts(), pi = payments.amounts();
        Amount s = next(si), p = next(pi);
        List<Row> rows = new ArrayList<>();
        while ((s != null || p != null) && rows.size() <= RelatedRecordsService.PAGE_SIZE) {
            LocalDate date = s == null ? p.date() : p == null ? s.date() : s.date().isBefore(p.date()) ? s.date() : p.date();
            BigDecimal shipment = null, payment = null;
            if (s != null && s.date().equals(date)) { shipment = s.amount(); s = next(si); }
            if (p != null && p.date().equals(date)) { payment = p.amount(); p = next(pi); }
            rows.add(new Row(date, shipment, payment));
        }
        boolean hasNext = rows.size() > RelatedRecordsService.PAGE_SIZE;
        if (hasNext) rows.removeLast();
        return new SchedulePage(rows, hasNext, shipments.total(), payments.total(), shipments.available(), payments.available());
    }

    private Side side(UUID projectId, boolean shipment, LocalDate afterDate) {
        boolean allowed = shipment ? canReadShipments() : canReadPayments();
        if (!allowed) return new Side(Collections.emptyIterator(), null, false);
        // Load the header separately: a join alone would not enforce header predicate policies.
        UUID headerId;
        if (shipment) {
            headerId = data.load(ShipmentSchedule.class).query("select e from ShipmentSchedule e where e.project.id = :project")
                    .parameter("project", projectId).fetchPlan(FetchPlan.BASE).optional().map(ShipmentSchedule::getId).orElse(null);
        } else {
            headerId = data.load(PaymentSchedule.class).query("select e from PaymentSchedule e where e.project.id = :project")
                    .parameter("project", projectId).fetchPlan(FetchPlan.BASE).optional().map(PaymentSchedule::getId).orElse(null);
        }
        if (headerId == null) return new Side(Collections.emptyIterator(), BigDecimal.ZERO, true);
        Class<?> itemType = shipment ? ShipmentScheduleItem.class : PaymentScheduleItem.class;
        if (policies.getRowLevelPolicies(metadata.getClass(itemType)).findAny().isPresent()) {
            // Arbitrary predicates cannot be expressed as SUM/GROUP BY. Keep reads bounded and
            // evaluate through DataManager; never silently replace secured totals with scalar totals.
            BigDecimal total = BigDecimal.ZERO;
            Iterator<Amount> source = securedAmounts(headerId, shipment, null);
            while (source.hasNext()) total = total.add(source.next().amount());
            return new Side(securedAmounts(headerId, shipment, afterDate), total, true);
        }
        String name = metadata.getClass(itemType).getName();
        String from = " from " + name + " e where e.schedule.id = :schedule";
        BigDecimal total = data.loadValue("select sum(e.amount)" + from, BigDecimal.class)
                .parameter("schedule", headerId).optional().orElse(BigDecimal.ZERO);
        String pageFrom = from + (afterDate == null ? "" : " and e.itemDate > :after");
        Map<String,Object> parameters = new HashMap<>();
        parameters.put("schedule", headerId);
        if (afterDate != null) parameters.put("after", afterDate);
        Iterator<Amount> grouped = new Batches<>(first -> {
            var loader = data.loadValues("select e.itemDate, sum(e.amount)" + pageFrom
                    + " group by e.itemDate order by e.itemDate")
                    .properties("date", "amount").firstResult(first).maxResults(BATCH_SIZE);
            parameters.forEach(loader::parameter);
            return loader.list().stream().map(value -> new Amount(value.getValue("date"), value.getValue("amount"))).toList();
        });
        return new Side(grouped, total, true);
    }

    private Iterator<Amount> securedAmounts(UUID headerId, boolean shipment, LocalDate afterDate) {
        String name = shipment ? "ShipmentScheduleItem" : "PaymentScheduleItem";
        String query = "select e from " + name + " e where e.schedule.id = :schedule"
                + (afterDate == null ? "" : " and e.itemDate > :after") + " order by e.itemDate, e.id";
        Map<String,Object> parameters = new HashMap<>();
        parameters.put("schedule", headerId);
        if (afterDate != null) parameters.put("after", afterDate);
        Iterator<Amount> items = new Batches<>(first -> {
            List<? extends ScheduleItem> values = shipment
                    ? data.load(ShipmentScheduleItem.class).query(query).parameters(parameters)
                        .fetchPlan(FetchPlan.BASE).firstResult(first).maxResults(BATCH_SIZE).list()
                    : data.load(PaymentScheduleItem.class).query(query).parameters(parameters)
                        .fetchPlan(FetchPlan.BASE).firstResult(first).maxResults(BATCH_SIZE).list();
            return values.stream().map(item -> new Amount(item.getItemDate(), item.getAmount())).toList();
        });
        return new Iterator<>() {
            private Amount pending = ProjectScheduleService.next(items);
            public boolean hasNext() { return pending != null; }
            public Amount next() {
                if (pending == null) throw new NoSuchElementException();
                LocalDate date = pending.date();
                BigDecimal sum = BigDecimal.ZERO;
                do { sum = sum.add(pending.amount()); pending = ProjectScheduleService.next(items); }
                while (pending != null && pending.date().equals(date));
                return new Amount(date, sum);
            }
        };
    }

    private static <T> T next(Iterator<T> iterator) { return iterator.hasNext() ? iterator.next() : null; }

    /** At most one fixed-size batch is retained, even for a date with thousands of items. */
    private static class Batches<T> implements Iterator<T> {
        private final IntFunction<List<T>> loader;
        private Iterator<T> batch = Collections.emptyIterator();
        private int offset;
        private boolean finished;
        Batches(IntFunction<List<T>> loader) { this.loader = loader; }
        public boolean hasNext() {
            if (!batch.hasNext() && !finished) {
                List<T> values = loader.apply(offset);
                offset += values.size();
                finished = values.size() < BATCH_SIZE;
                batch = values.iterator();
            }
            return batch.hasNext();
        }
        public T next() { if (!hasNext()) throw new NoSuchElementException(); return batch.next(); }
    }
}
