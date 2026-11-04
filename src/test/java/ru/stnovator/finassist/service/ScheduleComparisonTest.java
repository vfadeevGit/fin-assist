package ru.stnovator.finassist.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ScheduleComparisonTest {
    private final LocalDate date = LocalDate.of(2026, 5, 1);
    private ScheduleComparison.Entry entry(LocalDate d, String amount) {
        return new ScheduleComparison.Entry(d, new BigDecimal(amount));
    }
    @Test void aggregatesDatesAndKeepsExactDecimals() {
        var comparison = ScheduleComparison.compare(List.of(entry(date, "100.10"), entry(date, "20.20")),
                List.of(entry(date, "120.30"), entry(date.plusDays(1), "0.01")));
        assertThat(comparison.beforeTotal()).isEqualByComparingTo("120.30");
        assertThat(comparison.afterTotal()).isEqualByComparingTo("120.31");
        assertThat(comparison.delta()).isEqualByComparingTo("0.01");
        assertThat(comparison.rows()).hasSize(2);
        assertThat(comparison.rows().getFirst().changed()).isFalse();
        assertThat(comparison.rows().getLast().before()).isNull();
        assertThat(comparison.afterEnd()).isEqualTo(date.plusDays(1));
    }
    @Test void absenceIsDifferentFromAnExplicitZero() {
        var comparison = ScheduleComparison.compare(List.of(entry(date, "0")), List.of(entry(date.plusDays(1), "0")));
        assertThat(comparison.rows()).allMatch(ScheduleComparison.Row::changed);
        assertThat(comparison.rows().getFirst().after()).isNull();
    }
    @Test void emptyDraftIsNotDeletion() {
        var comparison = ScheduleComparison.compare(List.of(entry(date, "100")), List.of());
        assertThat(comparison.status()).isEqualTo(ScheduleComparison.Status.EMPTY);
        assertThat(comparison.delta()).isNull();
        assertThat(comparison.rows()).isEmpty();
    }
    @Test void incompleteDraftDoesNotProducePartialTotals() {
        var comparison = ScheduleComparison.compare(List.of(), List.of(new ScheduleComparison.Entry(null, BigDecimal.ONE)));
        assertThat(comparison.status()).isEqualTo(ScheduleComparison.Status.INCOMPLETE);
        assertThat(comparison.afterTotal()).isNull();
    }
}
