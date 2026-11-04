package ru.stnovator.finassist.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Date buckets compare complete schedules, not inferred links between individual items. */
public record ScheduleComparison(Status status, BigDecimal beforeTotal, BigDecimal afterTotal,
                                 LocalDate beforeEnd, LocalDate afterEnd, List<Row> rows) {
    public enum Status { COMPLETE, EMPTY, INCOMPLETE, UNAVAILABLE }
    public record Entry(LocalDate date, BigDecimal amount) {}
    public record Row(LocalDate date, BigDecimal before, BigDecimal after) {
        public BigDecimal delta() { return zero(after).subtract(zero(before)); }
        public boolean changed() { return before == null || after == null || before.compareTo(after) != 0; }
    }
    public ScheduleComparison { rows = List.copyOf(rows); }
    public BigDecimal delta() { return status == Status.COMPLETE ? afterTotal.subtract(beforeTotal) : null; }
    public static ScheduleComparison unavailable() { return empty(Status.UNAVAILABLE); }
    private static ScheduleComparison empty(Status status) {
        return new ScheduleComparison(status, null, null, null, null, List.of());
    }
    public static ScheduleComparison compare(List<Entry> before, List<Entry> after) {
        if (after.isEmpty()) return empty(Status.EMPTY);
        if (java.util.stream.Stream.concat(before.stream(), after.stream())
                .anyMatch(e -> e.date() == null || e.amount() == null)) return empty(Status.INCOMPLETE);
        var b = buckets(before); var a = buckets(after);
        SortedSet<LocalDate> dates = new TreeSet<>(b.keySet()); dates.addAll(a.keySet());
        return new ScheduleComparison(Status.COMPLETE, sum(b.values()), sum(a.values()),
                b.isEmpty() ? null : b.lastKey(), a.lastKey(),
                dates.stream().map(d -> new Row(d, b.get(d), a.get(d))).toList());
    }
    private static TreeMap<LocalDate, BigDecimal> buckets(List<Entry> items) {
        TreeMap<LocalDate, BigDecimal> result = new TreeMap<>();
        items.forEach(e -> result.merge(e.date(), e.amount(), BigDecimal::add));
        return result;
    }
    private static BigDecimal sum(Collection<BigDecimal> amounts) {
        return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private static BigDecimal zero(BigDecimal amount) { return amount == null ? BigDecimal.ZERO : amount; }
}
