package ru.stnovator.finassist.ai.schedule;

import org.apache.commons.csv.CSVFormat;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleTableTypes.*;

@Service
public class ScheduleTableReader {
    private static final int SAMPLE_ROWS = 20;
    private static final int SAMPLE_BUDGET = 30000;
    private final ScheduleImportProperties limits;

    public ScheduleTableReader(ScheduleImportProperties properties) { this.limits = properties; }

    public Inspection inspect(InputStream input, String fileName) {
        Table table;
        try {
            table = load(input, fileName, null);
        } catch (TableReadException failure) {
            if (failure.issues().stream().allMatch(issue -> issue.code().startsWith("CSV_"))) {
                return new Inspection(List.of(), failure.issues(), new CsvOptions("UTF-8", null));
            }
            throw failure;
        }
        var sheets = new ArrayList<Sheet>();
        int budget = SAMPLE_BUDGET;
        boolean sampleLimited = false;
        for (Grid grid : table.sheets()) {
            var sample = new ArrayList<List<Cell>>();
            budget -= 256 + 6 * grid.name().length();
            for (List<Value> row : grid.rows().subList(0, Math.min(SAMPLE_ROWS, grid.rows().size()))) {
                var cells = new ArrayList<Cell>();
                int cost = 8;
                for (int col = 0; col < row.size(); col++) {
                    Value value = row.get(col);
                    cost += 100 + 6 * (value.raw().length() + value.formatted().length());
                    cells.add(new Cell(col, value.raw(), value.formatted(), value.type()));
                }
                if (cost > budget) { sampleLimited = true; break; }
                sample.add(cells);
                budget -= cost;
            }
            sheets.add(new Sheet(grid.key(), grid.name(), sample));
        }
        return new Inspection(sheets, sampleLimited ? List.of(new Issue("file", "SAMPLE_TEXT_LIMIT")) : List.of(),
                table.csvOptions());
    }

    public List<InputRow> read(InputStream input, String fileName, Mapping mapping) {
        validateMapping(mapping);
        Table table = load(input, fileName, mapping.csvOptions());
        Grid grid = table.sheets().stream().filter(sheet -> sheet.key().equals(mapping.sheetKey()))
                .findFirst().orElseThrow(() -> failure("file", "SHEET_REQUIRED"));
        var result = new ArrayList<InputRow>();
        var issues = new ArrayList<Issue>();
        for (int index : mapping.excludedRows()) {
            if (index < 0 || index >= grid.rows().size()) issues.add(new Issue(grid.key(), "INVALID_EXCLUDED_ROW"));
        }
        for (int index = 0; index < grid.rows().size(); index++) {
            List<Value> row = grid.rows().get(index);
            if (row.stream().allMatch(value -> value.raw().isBlank())) continue;
            String source = grid.key() + "!" + (index + 1);
            Value date = value(row, mapping.dateColumn());
            Value amount = value(row, mapping.amountColumn());
            if (index < mapping.firstDataRow() || mapping.excludedRows().contains(index)) {
                if (!headerOrTotal(date, amount) || row.stream().anyMatch(Value::invalid)) {
                    issues.add(new Issue(source, "INVALID_EXCLUDED_ROW"));
                }
                continue;
            }
            if (date.invalid() || amount.invalid()) {
                issues.add(new Issue(source, date.type().startsWith("FORMULA") || amount.type().startsWith("FORMULA")
                        ? "FORMULA_REQUIRES_CORRECTION" : "CELL_REQUIRES_CORRECTION"));
                continue;
            }
            try {
                result.add(new InputRow(dateText(date, mapping.formats()), amountText(amount, mapping.formats()), source));
            } catch (IllegalArgumentException ex) {
                issues.add(new Issue(source, "INVALID_PARSE_OPTIONS"));
            }
            if (result.size() > limits.getMaxRows()) throw failure(source, "ROW_LIMIT");
        }
        if (!issues.isEmpty()) throw new TableReadException(issues);
        if (result.isEmpty()) throw failure(grid.key(), "NO_DATA_ROWS");
        return List.copyOf(result);
    }

    private Table load(InputStream input, String name, CsvOptions csvOptions) {
        validateLimits();
        String extension = name == null ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("csv", "xls", "xlsx").contains(extension)) throw failure("file", "UNSUPPORTED_FILE_TYPE");
        try {
            byte[] bytes = boundedBytes(input);
            if (extension.equals("csv")) return csv(bytes, csvOptions);
            new ScheduleWorkbookGuard(limits).check(bytes, extension.equals("xlsx"));
            return workbook(bytes);
        } catch (TableReadException ex) {
            throw ex;
        } catch (Exception ex) {
            var failure = failure("file", extension.equals("csv") ? "CSV_INVALID" : "INVALID_WORKBOOK");
            failure.initCause(ex);
            throw failure;
        }
    }

    private void validateLimits() {
        if (limits.getMaxFileBytes() < 1 || limits.getMaxFileBytes() > Integer.MAX_VALUE - 8
                || limits.getMaxInflatedBytes() < 1 || limits.getMaxRows() < 1 || limits.getMaxRows() > 1000000
                || limits.getMaxColumns() < 1 || limits.getMaxColumns() > 16384
                || limits.getMaxSheets() < 1 || limits.getMaxCells() < 1) {
            throw failure("file", "INVALID_LIMITS");
        }
    }

    private void validateMapping(Mapping mapping) {
        if (mapping == null) throw failure("file", "MAPPING_REQUIRED");
        if (mapping.sheetKey() == null || mapping.dateColumn() < 0 || mapping.amountColumn() < 0
                || mapping.dateColumn() == mapping.amountColumn() || mapping.firstDataRow() < 0
                || mapping.firstDataRow() > SAMPLE_ROWS || mapping.dateColumn() >= limits.getMaxColumns()
                || mapping.amountColumn() >= limits.getMaxColumns()) throw failure("file", "INVALID_MAPPING");
    }

    private byte[] boundedBytes(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        long count = 0;
        int length;
        while ((length = input.read(buffer)) != -1) {
            count += length;
            if (count > limits.getMaxFileBytes()) throw failure("file", "FILE_LIMIT");
            bytes.write(buffer, 0, length);
        }
        return bytes.toByteArray();
    }

    private Table workbook(byte[] bytes) throws IOException {
        var sheets = new ArrayList<Grid>();
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            boolean date1904 = book instanceof XSSFWorkbook xssf ? xssf.isDate1904()
                    : ((HSSFWorkbook) book).getWorkbook().isUsing1904DateWindowing();
            boolean reliableCache = false;
            if (book instanceof XSSFWorkbook xssf && xssf.getCTWorkbook().isSetCalcPr()) {
                var calc = xssf.getCTWorkbook().getCalcPr();
                reliableCache = calc.isSetCalcCompleted() && calc.getCalcCompleted()
                        && !calc.getFullCalcOnLoad() && !calc.getForceFullCalc();
            }
            var formatter = new DataFormatter(Locale.ROOT);
            formatter.setUseCachedValuesForFormulaCells(true);
            for (int sheet = 0; sheet < book.getNumberOfSheets(); sheet++) {
                var source = book.getSheetAt(sheet);
                var rows = new ArrayList<List<Value>>();
                for (int index = 0; index <= source.getLastRowNum(); index++) {
                    var row = source.getRow(index);
                    var values = new ArrayList<Value>();
                    if (row != null) {
                        for (int col = 0; col < row.getLastCellNum(); col++) {
                            var cell = row.getCell(col);
                            if (cell == null) { values.add(Value.empty()); continue; }
                            boolean formula = cell.getCellType() == CellType.FORMULA;
                            CellType type = formula ? cell.getCachedFormulaResultType() : cell.getCellType();
                            boolean cachePresent = cell instanceof XSSFCell xc && xc.getCTCell().isSetV()
                                    && !xc.getCTCell().getV().isBlank();
                            if (formula && (!reliableCache || !cachePresent || type == CellType.ERROR
                                    || type == CellType.BLANK || type == CellType.BOOLEAN)) {
                                values.add(new Value(cell.getCellFormula(), "", "FORMULA_UNRELIABLE", date1904));
                                continue;
                            }
                            String raw = switch (type) {
                                case STRING -> cell.getStringCellValue();
                                case NUMERIC -> cell instanceof XSSFCell xc ? xc.getRawValue()
                                        : BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString();
                                case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
                                case ERROR -> Byte.toString(cell.getErrorCellValue());
                                default -> "";
                            };
                            String format = type == CellType.ERROR ? raw : formatter.formatCellValue(cell);
                            values.add(new Value(raw, format, type.name(), date1904));
                        }
                    }
                    rows.add(values);
                }
                if (rows.stream().flatMap(List::stream).anyMatch(value -> !value.raw().isBlank())) {
                    sheets.add(new Grid("sheet:" + sheet, source.getSheetName(), rows));
                }
            }
        }
        return new Table(sheets, null);
    }

    private Table csv(byte[] bytes, CsvOptions options) throws IOException {
        var selected = options == null ? new CsvOptions("UTF-8", null) : options;
        String text;
        try {
            Charset charset = Charset.forName(selected.charset());
            if (!Set.of("UTF-8", "windows-1251", "UTF-16LE", "UTF-16BE").contains(charset.name())) {
                throw failure("csv", "CSV_ENCODING_REQUIRED");
            }
            text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\ufeff")) text = text.substring(1);
            if (text.indexOf('\0') >= 0) throw failure("csv", "CSV_ENCODING_REQUIRED");
        } catch (CharacterCodingException | IllegalArgumentException ex) {
            throw failure("csv", "CSV_ENCODING_REQUIRED");
        }
        Character delimiter = selected.delimiter();
        List<List<Value>> rows = null;
        if (delimiter == null) {
            int candidates = 0;
            TableReadException limitFailure = null;
            for (char candidate : new char[]{';', ',', '\t'}) {
                List<List<Value>> parsed;
                try { parsed = parseCsv(text, candidate); }
                catch (TableReadException ex) {
                    if (ex.issues().stream().anyMatch(issue -> issue.code().endsWith("_LIMIT"))) limitFailure = ex;
                    continue;
                } catch (IOException ex) { continue; }
                int width = parsed.isEmpty() ? 0 : parsed.getFirst().size();
                if (width > 1 && parsed.stream().allMatch(row -> row.size() == width)) {
                    candidates++;
                    delimiter = candidate;
                    rows = parsed;
                }
            }
            if (candidates == 0 && limitFailure != null) throw limitFailure;
            if (candidates != 1 || limitFailure != null) throw failure("csv", "CSV_DIALECT_REQUIRED");
        } else {
            if (delimiter != ';' && delimiter != ',' && delimiter != '\t') throw failure("csv", "CSV_DIALECT_REQUIRED");
            rows = parseCsv(text, delimiter);
        }
        return new Table(List.of(new Grid("csv", "CSV", rows)), new CsvOptions(selected.charset(), delimiter));
    }

    private List<List<Value>> parseCsv(String text, char delimiter) throws IOException {
        // Guard record width before Commons CSV allocates a whole record. The library owns actual CSV semantics.
        boolean quoted = false;
        int columns = 1;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c == '"') {
                if (quoted && index + 1 < text.length() && text.charAt(index + 1) == '"') index++;
                else quoted = !quoted;
            }
            if (!quoted && c == delimiter && ++columns > limits.getMaxColumns()) throw failure("csv", "COLUMN_LIMIT");
            if (!quoted && (c == '\n' || c == '\r')) columns = 1;
        }
        var rows = new ArrayList<List<Value>>();
        int cells = 0;
        try (var parser = CSVFormat.RFC4180.builder().setDelimiter(delimiter).setIgnoreEmptyLines(false).get()
                .parse(new StringReader(text))) {
            for (var record : parser) {
                if (rows.size() >= limits.getMaxRows() + SAMPLE_ROWS) throw failure("csv", "ROW_LIMIT");
                if (record.size() > limits.getMaxColumns()) throw failure("csv", "COLUMN_LIMIT");
                var row = new ArrayList<Value>();
                for (String raw : record) {
                    if (!raw.isBlank() && ++cells > limits.getMaxCells()) throw failure("csv", "CELL_LIMIT");
                    row.add(new Value(raw, raw, "STRING", false));
                }
                rows.add(row);
            }
        } catch (java.io.UncheckedIOException ex) {
            throw failure("csv", "CSV_INVALID");
        }
        return rows;
    }

    private String dateText(Value value, ParseOptions formats) {
        if (!value.type().equals("NUMERIC")) return value.raw();
        double serial = Double.parseDouble(value.raw());
        if (!DateUtil.isValidExcelDate(serial) || serial != Math.rint(serial)) return value.raw();
        var date = DateUtil.getLocalDateTime(serial, value.date1904()).toLocalDate();
        return formats == null ? date.toString() : date.format(DateTimeFormatter.ofPattern(formats.datePattern(), Locale.ROOT));
    }

    private String amountText(Value value, ParseOptions formats) {
        if (!value.type().equals("NUMERIC")) return value.raw();
        String text = new BigDecimal(value.raw()).stripTrailingZeros().toPlainString();
        return formats == null ? text : text.replace('.', formats.decimalSeparator());
    }

    private boolean headerOrTotal(Value date, Value amount) {
        String dateLabel = date.raw().strip().toLowerCase(Locale.ROOT);
        String amountLabel = amount.raw().strip().toLowerCase(Locale.ROOT);
        boolean dateHeader = Set.of("date", "дата").contains(dateLabel);
        boolean amountHeader = Set.of("amount", "сумма").contains(amountLabel);
        return (dateHeader && (amountHeader || amountLabel.isEmpty()))
                || (dateLabel.isEmpty() && amountHeader)
                || Set.of("total", "subtotal", "итого", "всего").contains(dateLabel);
    }

    private Value value(List<Value> row, int column) { return column < row.size() ? row.get(column) : Value.empty(); }
    private TableReadException failure(String source, String code) { return new TableReadException(List.of(new Issue(source, code))); }
    private record Grid(String key, String name, List<List<Value>> rows) {}
    private record Table(List<Grid> sheets, CsvOptions csvOptions) {}
    private record Value(String raw, String formatted, String type, boolean date1904) {
        static Value empty() { return new Value("", "", "BLANK", false); }
        boolean invalid() { return type.equals("ERROR") || type.equals("BOOLEAN") || type.equals("FORMULA_UNRELIABLE"); }
    }
}
