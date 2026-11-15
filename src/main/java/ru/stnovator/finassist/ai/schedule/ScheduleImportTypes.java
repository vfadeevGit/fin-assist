package ru.stnovator.finassist.ai.schedule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Internal import values; these records do not participate in Jmix metadata. */
public final class ScheduleImportTypes {
    private ScheduleImportTypes() {
    }

    public enum Kind { PAYMENT, SHIPMENT }
    public enum Mode { CREATE, FILL, REPLACE, ADDENDUM }
    public record Row(LocalDate date, BigDecimal amount) {}
    public record InputRow(String dateText, String amountText, String source) {}
    /** Use a zero groupingSeparator when the input has no digit grouping. */
    public record ParseOptions(String datePattern, char decimalSeparator, char groupingSeparator) {}
    public record Issue(String source, String code) {}
    public record Normalized(List<Row> rows, List<Issue> issues) {
        public Normalized {
            issues = List.copyOf(issues);
            rows = issues.isEmpty() ? List.copyOf(rows) : List.of();
        }
    }
}
