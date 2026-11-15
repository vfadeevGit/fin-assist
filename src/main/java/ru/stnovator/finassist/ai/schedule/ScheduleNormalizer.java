package ru.stnovator.finassist.ai.schedule;

import org.springframework.stereotype.Service;
import ru.stnovator.finassist.service.ScheduleComparison;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalQueries;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

/** Parses a complete input without rounding or exposing a partial schedule. */
@Service
public class ScheduleNormalizer {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999999.99");

    /** Without options only ISO dates and ungrouped decimal-dot amounts are accepted. */
    public Normalized normalize(List<InputRow> input, ParseOptions options) {
        DateTimeFormatter dateFormat;
        try {
            dateFormat = dateFormat(options);
        } catch (IllegalArgumentException ignored) {
            return new Normalized(List.of(), input.stream()
                    .map(row -> new Issue(row.source(), "INVALID_PARSE_OPTIONS")).toList());
        }
        char decimal = options == null ? '.' : options.decimalSeparator();
        char grouping = options == null ? '\0' : options.groupingSeparator();
        Pattern amountFormat = amountFormat(decimal, grouping);
        var issues = new ArrayList<Issue>();
        var groups = new TreeMap<LocalDate, List<ParsedAmount>>();

        for (InputRow row : input) {
            LocalDate date = parseDate(row, dateFormat, issues);
            BigDecimal amount = parseAmount(row, amountFormat, decimal, grouping, issues);
            if (date != null && amount != null) {
                groups.computeIfAbsent(date, ignored -> new ArrayList<>())
                        .add(new ParsedAmount(amount, row.source()));
            }
        }

        var rows = new ArrayList<Row>();
        groups.forEach((date, values) -> {
            BigDecimal amount = values.stream().map(ParsedAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            String code = amount.signum() <= 0 ? "NON_POSITIVE_AMOUNT"
                    : amount.compareTo(MAX_AMOUNT) > 0 ? "AMOUNT_OVERFLOW" : null;
            if (code != null) {
                values.forEach(value -> issues.add(new Issue(value.source(), code)));
            } else {
                rows.add(new Row(date, amount));
            }
        });
        return new Normalized(rows, issues);
    }

    public ScheduleComparison compare(List<Row> before, List<Row> after) {
        return ScheduleComparison.compare(entries(before), entries(after));
    }

    private List<ScheduleComparison.Entry> entries(List<Row> rows) {
        return rows.stream().map(row -> new ScheduleComparison.Entry(row.date(), row.amount())).toList();
    }

    private LocalDate parseDate(InputRow row, DateTimeFormatter format, List<Issue> issues) {
        if (row.dateText() != null) {
            try {
                var parsed = format.parse(row.dateText().strip());
                if (parsed.query(TemporalQueries.localTime()) == null) {
                    return LocalDate.from(parsed);
                }
            } catch (DateTimeException ignored) {
                // Preserve the original input reference for correction by the user.
            }
        }
        issues.add(new Issue(row.source(), "INVALID_DATE"));
        return null;
    }

    private DateTimeFormatter dateFormat(ParseOptions options) {
        if (options == null) {
            return DateTimeFormatter.ISO_LOCAL_DATE;
        }
        char grouping = options.groupingSeparator();
        if (options.datePattern() == null || options.datePattern().isBlank()
                || (options.decimalSeparator() != '.' && options.decimalSeparator() != ',')
                || grouping == options.decimalSeparator() || Character.isDigit(grouping)
                || grouping == '+' || grouping == '-') {
            throw new IllegalArgumentException("Invalid date or number format options");
        }
        return new DateTimeFormatterBuilder().appendPattern(options.datePattern())
                .parseDefaulting(ChronoField.ERA, 1).toFormatter(Locale.ROOT)
                .withResolverStyle(ResolverStyle.STRICT);
    }

    private BigDecimal parseAmount(InputRow row, Pattern format, char decimal, char grouping, List<Issue> issues) {
        String text = row.amountText() == null ? "" : row.amountText().strip();
        if (!format.matcher(text).matches()) {
            issues.add(new Issue(row.source(), "INVALID_AMOUNT"));
            return null;
        }
        if (grouping != '\0') {
            text = text.replace(String.valueOf(grouping), "");
        }
        BigDecimal amount = new BigDecimal(text.replace(decimal, '.'));
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ignored) {
            issues.add(new Issue(row.source(), "AMOUNT_SCALE"));
            return null;
        }
        if (amount.abs().compareTo(MAX_AMOUNT) > 0) {
            issues.add(new Issue(row.source(), "AMOUNT_OVERFLOW"));
            return null;
        }
        return amount;
    }

    private Pattern amountFormat(char decimal, char grouping) {
        String integer = grouping == '\0' ? "[0-9]+"
                : "(?:[0-9]+|[0-9]{1,3}(?:" + Pattern.quote(String.valueOf(grouping)) + "[0-9]{3})+)";
        return Pattern.compile("[+-]?" + integer + "(?:" + Pattern.quote(String.valueOf(decimal)) + "[0-9]+)?");
    }

    private record ParsedAmount(BigDecimal amount, String source) {
    }
}
