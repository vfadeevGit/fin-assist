package ru.stnovator.finassist.ai.schedule;

import org.springframework.ai.tool.annotation.ToolParam;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleTableTypes.*;

/** Nullable JSON boundary; internal mapping and persisted parse options remain strict. */
public record ScheduleMappingRequest(
        @ToolParam(description = "Exact worksheet key returned by inspection; never guess among plausible sheets") String sheetKey,
        @ToolParam(description = "Required zero-based date column index; must be a whole number") BigDecimal dateColumn,
        @ToolParam(description = "Required zero-based amount column index, different from dateColumn; must be a whole number") BigDecimal amountColumn,
        @ToolParam(description = "Required zero-based first data row; preceding rows must be recognizable headers; must be a whole number") BigDecimal firstDataRow,
        @ToolParam(required = false, description = "Zero-based recognizable header/total rows to exclude; whole numbers only; never exclude payments") List<BigDecimal> excludedRows,
        @ToolParam(required = false, description = "Omit or null for automatic numeric Excel dates/amounts and ISO text with decimal-dot numbers. An empty object or all-null/empty fields also means automatic") Formats formats,
        @ToolParam(required = false, description = "CSV options only; omit for Excel and automatic UTF-8 CSV detection") Csv csvOptions) {

    public record Formats(
            @ToolParam(required = false, description = "Explicit Java date pattern for text dates, e.g. dd.MM.uuuu; MM is month, mm is minute and unsupported. Omit/null with the other fields for default ISO text or automatic Excel parsing") String datePattern,
            @ToolParam(required = false, description = "Explicit single-character decimal separator: . or ,; required with an explicit date pattern") String decimalSeparator,
            @ToolParam(required = false, description = "Single-character digit grouping separator, or null/empty for no grouping") String groupingSeparator) {}

    public record Csv(
            @ToolParam(required = false, description = "CSV character encoding; null/empty defaults to UTF-8") String charset,
            @ToolParam(required = false, description = "One of semicolon, comma or tab; null/empty requests detection") String delimiter) {}

    Mapping toMapping() {
        if (sheetKey == null || sheetKey.isBlank()) throw invalid("file", "INVALID_MAPPING");
        int date = index(dateColumn, "INVALID_MAPPING");
        int amount = index(amountColumn, "INVALID_MAPPING");
        int first = index(firstDataRow, "INVALID_MAPPING");
        if (date == amount) throw invalid("file", "INVALID_MAPPING");
        Set<Integer> excluded = excludedRows == null ? Set.of() : excludedRows.stream()
                .map(row -> index(row, "INVALID_EXCLUDED_ROW")).collect(java.util.stream.Collectors.toUnmodifiableSet());
        // The bounded reader remains authoritative for upper limits, actual sheet/cell bounds and all exclusions.
        return new Mapping(sheetKey, date, amount, first, excluded, parseOptions(formats), csv());
    }

    private static int index(BigDecimal value, String code) {
        if (value == null || value.signum() < 0) throw invalid("file", code);
        try {
            // Preserve JSON fractions until validation instead of letting Jackson truncate them into another column.
            return value.intValueExact();
        } catch (ArithmeticException invalidIndex) {
            throw invalid("file", code);
        }
    }

    static ParseOptions parseOptions(Formats formats) {
        if (formats == null || empty(formats.datePattern()) && empty(formats.decimalSeparator())
                && empty(formats.groupingSeparator())) return null;
        String date = formats.datePattern();
        String decimal = formats.decimalSeparator();
        String grouping = formats.groupingSeparator();
        if (date == null || date.isBlank() || !(".".equals(decimal) || ",".equals(decimal))
                || !empty(grouping) && grouping.length() != 1) throw invalid("file", "INVALID_PARSE_OPTIONS");
        char separator = empty(grouping) ? '\0' : grouping.charAt(0);
        if (separator == decimal.charAt(0) || Character.isDigit(separator) || separator == '+' || separator == '-'
                || separator != '\0' && Character.isISOControl(separator)) throw invalid("file", "INVALID_PARSE_OPTIONS");
        try {
            var formatter = new DateTimeFormatterBuilder().appendPattern(date).parseDefaulting(ChronoField.ERA, 1)
                    .toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
            var sample = LocalDate.of(2000, 11, 23);
            if (!sample.equals(LocalDate.from(formatter.parse(sample.format(formatter))))) {
                throw invalid("file", "INVALID_PARSE_OPTIONS");
            }
        } catch (DateTimeException | IllegalArgumentException invalidPattern) {
            throw invalid("file", "INVALID_PARSE_OPTIONS");
        }
        return new ParseOptions(date, decimal.charAt(0), separator);
    }

    private CsvOptions csv() {
        if (csvOptions == null) return null;
        String delimiter = csvOptions.delimiter();
        if (!empty(delimiter) && !(";".equals(delimiter) || ",".equals(delimiter) || "\t".equals(delimiter))) {
            throw invalid("csv", "CSV_DIALECT_REQUIRED");
        }
        return new CsvOptions(empty(csvOptions.charset()) ? null : csvOptions.charset(),
                empty(delimiter) ? null : delimiter.charAt(0));
    }

    private static boolean empty(String value) { return value == null || value.isEmpty(); }
    private static TableReadException invalid(String source, String code) {
        return new TableReadException(List.of(new Issue(source, code)));
    }
}
