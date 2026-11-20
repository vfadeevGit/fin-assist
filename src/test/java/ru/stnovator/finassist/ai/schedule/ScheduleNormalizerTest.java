package ru.stnovator.finassist.ai.schedule;

import org.junit.jupiter.api.Test;
import ru.stnovator.finassist.service.ScheduleComparison;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

class ScheduleNormalizerTest {
    private final ScheduleNormalizer normalizer = new ScheduleNormalizer();

    @Test
    void groupsDatesAndComparesFullSnapshots() {
        var result = normalizer.normalize(List.of(
                input("2026-12-15", "200", "sheet!A4:B4"),
                input("2026-12-01", "50", "sheet!A2:B2"),
                input("2026-12-01", "100", "sheet!A3:B3")), null);

        assertThat(result.issues()).isEmpty();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows()).extracting(Row::date)
                .containsExactly(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 15));
        assertThat(result.rows().getFirst().amount()).isEqualByComparingTo("150");
        assertThat(result.rows().getLast().amount()).isEqualByComparingTo("200");

        var diff = normalizer.compare(List.of(row("2026-12-01", "100"), row("2026-12-10", "200")), result.rows());
        assertThat(diff.status()).isEqualTo(ScheduleComparison.Status.COMPLETE);
        assertThat(diff.delta()).isEqualByComparingTo("50");
        assertThat(diff.rows()).hasSize(3);
        assertThat(diff.rows().get(0).delta()).isEqualByComparingTo("50");
        assertThat(diff.rows().get(1).delta()).isEqualByComparingTo("-200");
        assertThat(diff.rows().get(2).delta()).isEqualByComparingTo("200");
    }

    @Test
    void rejectsInvalidRowsAsWhole() {
        var result = normalizer.normalize(List.of(
                input("2026-12-01", "100", "valid"),
                input("2026-02-30", "100", "impossible-day"),
                input("03/04/2026", "100", "ambiguous-date"),
                input("2026-12-02", "not-money", "bad-amount")), null);

        assertThat(result.rows()).isEmpty();
        assertThat(result.issues()).extracting(Issue::source)
                .containsExactlyInAnyOrder("impossible-day", "ambiguous-date", "bad-amount");
        assertThat(result.issues()).allSatisfy(issue -> assertThat(issue.code()).isNotBlank());
    }

    @Test
    void rejectsNonPositiveGroupedAmount() {
        var positive = normalizer.normalize(List.of(input("2026-12-01", "100", "one"),
                input("2026-12-01", "-20", "two")), null);
        assertThat(positive.issues()).isEmpty();
        assertThat(positive.rows().getFirst().amount()).isEqualByComparingTo("80");

        for (String amount : List.of("-100", "-120")) {
            var invalid = normalizer.normalize(List.of(input("2026-12-01", "100", "one"),
                    input("2026-12-01", amount, "two"), input("2026-12-02", "50", "valid")), null);
            assertThat(invalid.rows()).isEmpty();
            assertThat(invalid.issues()).extracting(Issue::source).containsExactly("one", "two");
        }
    }

    @Test
    void rejectsOverflowAfterGrouping() {
        var result = normalizer.normalize(List.of(input("2026-12-01", "99999999999999999.99", "max"),
                input("2026-12-01", "0.01", "extra")), null);
        assertThat(result.rows()).isEmpty();
        assertThat(result.issues()).extracting(Issue::source).containsExactly("max", "extra");

        var validBoundary = normalizer.normalize(List.of(input("2026-12-01", "99999999999999999.99", "max")), null);
        assertThat(validBoundary.issues()).isEmpty();
        assertThat(validBoundary.rows().getFirst().amount()).isEqualByComparingTo("99999999999999999.99");

        var invalidRow = normalizer.normalize(List.of(input("2026-12-01", "100000000000000000", "too-big")), null);
        assertThat(invalidRow.rows()).isEmpty();
        assertThat(invalidRow.issues()).extracting(Issue::source).containsExactly("too-big");
    }

    @Test
    void doesNotRound() {
        var invalid = normalizer.normalize(List.of(input("2026-12-01", "1.001", "precision")), null);
        assertThat(invalid.rows()).isEmpty();
        assertThat(invalid.issues()).extracting(Issue::source).containsExactly("precision");

        var exact = normalizer.normalize(List.of(input("2026-12-01", "1.000", "exact")), null);
        assertThat(exact.issues()).isEmpty();
        assertThat(exact.rows().getFirst().amount()).isEqualByComparingTo("1.00");
    }

    @Test
    void equalAmountsIgnoreScale() {
        var diff = normalizer.compare(List.of(row("2026-12-01", "1.00")), List.of(row("2026-12-01", "1.0")));
        assertThat(diff.delta()).isEqualByComparingTo("0");
        assertThat(diff.rows()).noneMatch(ScheduleComparison.Row::changed);
    }

    @Test
    void equalTotalsDoNotMeanEqualSchedules() {
        var diff = normalizer.compare(List.of(row("2026-12-01", "100")), List.of(row("2026-12-02", "100")));
        assertThat(diff.delta()).isEqualByComparingTo("0");
        assertThat(diff.rows()).hasSize(2).allMatch(ScheduleComparison.Row::changed);
    }

    @Test
    void parsesExplicitFormatsStrictly() {
        var options = new ParseOptions("dd/MM/yyyy", ',', ' ');
        var result = normalizer.normalize(List.of(input("03/04/2026", "1 234,50", "formatted")), options);
        assertThat(result.issues()).isEmpty();
        assertThat(result.rows().getFirst().date()).isEqualTo(LocalDate.of(2026, 4, 3));
        assertThat(result.rows().getFirst().amount()).isEqualByComparingTo("1234.50");

        var invalid = normalizer.normalize(List.of(input("31/04/2026", "100,00", "invalid-day"),
                input("03/04/2026", "12 34,50", "invalid-grouping")), options);
        assertThat(invalid.rows()).isEmpty();
        assertThat(invalid.issues()).extracting(Issue::source)
                .containsExactlyInAnyOrder("invalid-day", "invalid-grouping");
    }

    @Test
    void rejectsMissingFieldsAndImplicitNumberFormats() {
        var result = normalizer.normalize(List.of(input(null, "100", "missing-date"),
                input("2026-12-01", null, "missing-amount"),
                input("2026-12-01", "1,000", "ambiguous-number"),
                input("2026-12-01", "1e2", "exponent")), null);
        assertThat(result.rows()).isEmpty();
        assertThat(result.issues()).extracting(Issue::source)
                .containsExactlyInAnyOrder("missing-date", "missing-amount", "ambiguous-number", "exponent");
    }

    @Test
    void rejectsInvalidParseOptionsWithoutReinterpretingAmounts() {
        var inputs = List.of(input("2026-12-01", "123,45", "original"));
        for (var options : List.of(new ParseOptions("yyyy-MM-dd", ',', ','),
                new ParseOptions(null, '.', '\0'), new ParseOptions("invalid-pattern", '.', '\0'),
                new ParseOptions("yyyy-MM-dd", '.', '1'))) {
            var result = normalizer.normalize(inputs, options);
            assertThat(result.rows()).isEmpty();
            assertThat(result.issues()).extracting(Issue::source).containsExactly("original");
        }
    }

    @Test
    void rejectsTimeInDatesAndDoesNotInventAYear() {
        var timed = normalizer.normalize(List.of(input("2026-12-01T12:00", "100", "time")),
                new ParseOptions("yyyy-MM-dd'T'HH:mm", '.', '\0'));
        assertThat(timed.rows()).isEmpty();
        assertThat(timed.issues()).extracting(Issue::source).containsExactly("time");

        var yearless = normalizer.normalize(List.of(input("01.12", "100", "no-year")),
                new ParseOptions("dd.MM", '.', '\0'));
        assertThat(yearless.rows()).isEmpty();
        assertThat(yearless.issues()).extracting(Issue::source).containsExactly("no-year");
    }

    @Test
    void normalizedSnapshotsAreImmutableAndNeverPartial() {
        var rows = new ArrayList<>(List.of(row("2026-12-01", "100")));
        var issues = new ArrayList<Issue>();
        var result = new Normalized(rows, issues);
        rows.clear();
        issues.add(new Issue("source", "INVALID_DATE"));
        assertThat(result.rows()).hasSize(1);
        assertThat(result.issues()).isEmpty();
        assertThatThrownBy(() -> result.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.issues().clear()).isInstanceOf(UnsupportedOperationException.class);

        var invalid = new Normalized(List.of(row("2026-12-01", "100")), issues);
        assertThat(invalid.rows()).isEmpty();
        assertThat(invalid.issues()).hasSize(1);
    }

    private InputRow input(String date, String amount, String source) {
        return new InputRow(date, amount, source);
    }

    private Row row(String date, String amount) {
        return new Row(LocalDate.parse(date), new BigDecimal(amount));
    }
}
