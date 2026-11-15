package ru.stnovator.finassist.ai.schedule;

import java.util.List;
import java.util.Set;

import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

/** Internal table inspection values. All row and column indexes in mappings are zero-based. */
public final class ScheduleTableTypes {
    private ScheduleTableTypes() {}

    public record Cell(int column, String raw, String formatted, String type) {}
    public record Sheet(String key, String name, List<List<Cell>> sample) {
        public Sheet { sample = sample.stream().map(List::copyOf).toList(); }
    }
    /** Null delimiter requests detection; encoding defaults strictly to UTF-8. */
    public record CsvOptions(String charset, Character delimiter) {
        public CsvOptions { charset = charset == null ? "UTF-8" : charset; }
    }
    public record Inspection(List<Sheet> sheets, List<Issue> issues, CsvOptions csvOptions) {
        public Inspection { sheets = List.copyOf(sheets); issues = List.copyOf(issues); }
    }
    /** firstDataRow confirms prefix header labels; exclusions may reference their repeats or recognizable headers/totals. */
    public record Mapping(String sheetKey, int dateColumn, int amountColumn, int firstDataRow,
                          Set<Integer> excludedRows, ParseOptions formats, CsvOptions csvOptions) {
        public Mapping { excludedRows = excludedRows == null ? Set.of() : Set.copyOf(excludedRows); }
        public Mapping(String sheetKey, int dateColumn, int amountColumn, int firstDataRow,
                       Set<Integer> excludedRows, ParseOptions formats) {
            this(sheetKey, dateColumn, amountColumn, firstDataRow, excludedRows, formats, null);
        }
    }
    /** Failures expose source-linked machine-readable issues and never partial rows. */
    public static final class TableReadException extends RuntimeException {
        private final List<Issue> issues;
        public TableReadException(List<Issue> issues) {
            super(issues.isEmpty() ? "Table import failed" : issues.getFirst().code());
            this.issues = List.copyOf(issues);
        }
        public List<Issue> issues() { return issues; }
    }
}
