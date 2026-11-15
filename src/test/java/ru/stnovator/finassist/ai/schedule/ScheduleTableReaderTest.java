package ru.stnovator.finassist.ai.schedule;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleTableTypes.*;

class ScheduleTableReaderTest {
    private final ScheduleTableReader reader = new ScheduleTableReader(new ScheduleImportProperties());
    private final ScheduleNormalizer normalizer = new ScheduleNormalizer();

    // Catches treating formatted Excel numbers as strings or accepting only one Excel container.
    @Test
    void readsXlsAndXlsx() throws Exception {
        for (boolean xlsx : List.of(false, true)) {
            try (Workbook book = xlsx ? new XSSFWorkbook() : new HSSFWorkbook()) {
                var sheet = book.createSheet("Schedule");
                sheet.createRow(0).createCell(0).setCellValue(LocalDate.of(2026, 12, 1));
                sheet.getRow(0).createCell(1).setCellValue(1234.5);
                var money = book.createCellStyle();
                money.setDataFormat(book.createDataFormat().getFormat("#,##0.00"));
                sheet.getRow(0).getCell(1).setCellStyle(money);
                byte[] bytes = bytes(book);
                String name = xlsx ? "schedule.xlsx" : "schedule.xls";
                var inspection = reader.inspect(stream(bytes), name);
                assertThat(inspection.sheets()).hasSize(1);
                var rows = reader.read(stream(bytes), name, mapping(inspection.sheets().getFirst().key(), null));
                assertThat(normalizer.normalize(rows, null).rows())
                        .containsExactly(new Row(LocalDate.of(2026, 12, 1), new BigDecimal("1234.50")));
            }
        }
    }

    // Catches splitting a decimal comma or ignoring quotes/BOM.
    @Test
    void readsQuotedCsvWithBomAndDecimalComma() {
        byte[] bytes = "\ufeff\"01.12.2026\";\"1 234,50\"\r\n".getBytes(StandardCharsets.UTF_8);
        var inspection = reader.inspect(stream(bytes), "schedule.csv");
        assertThat(inspection.issues()).isEmpty();
        assertThat(inspection.csvOptions().delimiter()).isEqualTo(';');
        var formats = new ParseOptions("dd.MM.yyyy", ',', ' ');
        var rows = reader.read(stream(bytes), "schedule.csv", mapping("csv", formats));
        assertThat(rows).hasSize(1);
        assertThat(normalizer.normalize(rows, formats).rows())
                .containsExactly(new Row(LocalDate.of(2026, 12, 1), new BigDecimal("1234.50")));
    }

    @Test
    void ambiguousColumnsRequireMapping() throws Exception {
        try (var book = new XSSFWorkbook()) {
            for (String name : List.of("Payments", "Alternative")) {
                var row = book.createSheet(name).createRow(0);
                row.createCell(0).setCellValue("2026-12-01");
                row.createCell(1).setCellValue(10);
                row.createCell(2).setCellValue(20);
            }
            byte[] bytes = bytes(book);
            assertThat(reader.inspect(stream(bytes), "a.xlsx").sheets()).hasSize(2);
            assertIssue(() -> reader.read(stream(bytes), "a.xlsx", null), "MAPPING_REQUIRED");
            assertThat(reader.read(stream(bytes), "a.xlsx",
                    new Mapping("sheet:1", 0, 2, 0, Set.of(), null)).getFirst().amountText()).isEqualTo("20");
        }
    }

    @Test
    void readsAllRowsBeyondAiSample() {
        StringBuilder csv = new StringBuilder();
        for (int n = 0; n < 45; n++) csv.append("2026-12-01;1.00\n");
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(reader.inspect(stream(bytes), "a.csv").sheets().getFirst().sample()).hasSize(20);
        assertThat(reader.read(stream(bytes), "a.csv", mapping("csv", null))).hasSize(45);
    }

    @Test
    void rejectsOversizedWorkbookWithoutTruncation() throws Exception {
        var limits = new ScheduleImportProperties();
        limits.setMaxRows(2);
        var limited = new ScheduleTableReader(limits);
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet();
            for (int n = 0; n < 3; n++) {
                sheet.createRow(n).createCell(0).setCellValue("2026-12-01");
                sheet.getRow(n).createCell(1).setCellValue(1);
            }
            byte[] bytes = bytes(book);
            assertIssue(() -> limited.read(stream(bytes), "a.xlsx", mapping("sheet:0", null)), "ROW_LIMIT");
        }
    }

    @Test
    void handles1904DateSystem() throws Exception {
        try (var book = new XSSFWorkbook()) {
            book.getCTWorkbook().getWorkbookPr().setDate1904(true);
            var row = book.createSheet().createRow(0);
            row.createCell(0).setCellValue(LocalDate.of(2026, 12, 1));
            row.createCell(1).setCellValue(10);
            var formats = new ParseOptions("dd.MM.yyyy", ',', '\0');
            var rows = reader.read(stream(bytes(book)), "a.xlsx", mapping("sheet:0", formats));
            assertThat(rows.getFirst().dateText()).isEqualTo("01.12.2026");
            assertThat(normalizer.normalize(rows, formats).issues()).isEmpty();
        }
    }

    @Test
    void formulaWithoutReliableCachedValueRequiresCorrection() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var row = book.createSheet().createRow(0);
            row.createCell(0).setCellValue("2026-12-01");
            row.createCell(1).setCellFormula("1+2");
            assertIssue(() -> reader.read(stream(bytes(book)), "a.xlsx", mapping("sheet:0", null)),
                    "FORMULA_REQUIRES_CORRECTION");
            // Cache supplied by the producer; the reader must never evaluate this formula itself.
            row.getCell(1).setCellValue(7);
            var calc = book.getCTWorkbook().isSetCalcPr() ? book.getCTWorkbook().getCalcPr()
                    : book.getCTWorkbook().addNewCalcPr();
            calc.setCalcCompleted(true);
            calc.setFullCalcOnLoad(false);
            calc.setForceFullCalc(false);
            assertThat(reader.read(stream(bytes(book)), "a.xlsx", mapping("sheet:0", null))
                    .getFirst().amountText()).isEqualTo("7");
            row.getCell(1).setCellErrorValue((byte) 7);
            assertIssue(() -> reader.read(stream(bytes(book)), "a.xlsx", mapping("sheet:0", null)),
                    "FORMULA_REQUIRES_CORRECTION");
        }
    }

    @Test
    void explicitEncodingIsRequiredForMalformedUtf8() {
        byte[] bytes = "Дата;Сумма\n2026-12-01;10\n".getBytes(Charset.forName("windows-1251"));
        assertThat(reader.inspect(stream(bytes), "a.csv").issues()).extracting(Issue::code)
                .containsExactly("CSV_ENCODING_REQUIRED");
        assertIssue(() -> reader.read(stream(bytes), "a.csv", mapping("csv", null)), "CSV_ENCODING_REQUIRED");
        var mapping = new Mapping("csv", 0, 1, 1, Set.of(), null, new CsvOptions("windows-1251", ';'));
        assertThat(reader.read(stream(bytes), "a.csv", mapping)).hasSize(1);
    }

    @Test
    void ambiguousDialectNeedsExplicitDelimiter() {
        byte[] bytes = "2026-12-01;1,25\n2026-12-02;2,50\n".getBytes(StandardCharsets.UTF_8);
        assertThat(reader.inspect(stream(bytes), "a.csv").issues()).extracting(Issue::code)
                .containsExactly("CSV_DIALECT_REQUIRED");
        assertIssue(() -> reader.read(stream(bytes), "a.csv", mapping("csv", null)), "CSV_DIALECT_REQUIRED");
        assertThat(reader.read(stream(bytes), "a.csv", new Mapping("csv", 0, 1, 0, Set.of(),
                new ParseOptions("yyyy-MM-dd", ',', '\0'), new CsvOptions("UTF-8", ';')))).hasSize(2);
    }

    @Test
    void exclusionsCannotHideInvalidDataRows() {
        byte[] bytes = "Date;Amount\n2026-12-01;10\nbad date;broken\nTotal;10\n".getBytes(StandardCharsets.UTF_8);
        assertIssue(() -> reader.read(stream(bytes), "a.csv", new Mapping("csv", 0, 1, 1,
                Set.of(2, 3), null)), "INVALID_EXCLUDED_ROW");
        var rows = reader.read(stream(bytes), "a.csv", new Mapping("csv", 0, 1, 1, Set.of(3), null));
        assertThat(rows).hasSize(2);
        assertThat(normalizer.normalize(rows, null).issues()).extracting(Issue::source).contains("csv!3");
    }

    @Test
    void byteAndDecompressionLimitsAreCheckedDuringReading() throws Exception {
        var limits = new ScheduleImportProperties();
        limits.setMaxFileBytes(8);
        assertIssue(() -> new ScheduleTableReader(limits).inspect(stream(new byte[9]), "a.xlsx"), "FILE_LIMIT");
        limits.setMaxFileBytes(1024 * 1024);
        limits.setMaxInflatedBytes(1024);
        var zipBytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new ZipEntry("padding.bin"));
            zip.write(new byte[1025]);
            zip.closeEntry();
        }
        assertIssue(() -> new ScheduleTableReader(limits).inspect(stream(zipBytes.toByteArray()), "a.xlsx"),
                "DECOMPRESSION_LIMIT");
    }

    @Test
    void rejectsColumnSheetAndCellLimitsAcrossWholeWorkbook() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var row = book.createSheet().createRow(0);
            row.createCell(0).setCellValue("2026-12-01");
            row.createCell(1).setCellValue(10);
            var limits = new ScheduleImportProperties();
            limits.setMaxColumns(1);
            assertIssue(() -> new ScheduleTableReader(limits).inspect(stream(bytes(book)), "a.xlsx"), "COLUMN_LIMIT");
            limits.setMaxColumns(100);
            limits.setMaxCells(1);
            assertIssue(() -> new ScheduleTableReader(limits).inspect(stream(bytes(book)), "a.xlsx"), "CELL_LIMIT");
            limits.setMaxCells(200000);
            limits.setMaxSheets(1);
            book.createSheet().createRow(0).createCell(0).setCellValue("second");
            assertIssue(() -> new ScheduleTableReader(limits).inspect(stream(bytes(book)), "a.xlsx"), "SHEET_LIMIT");
        }
    }

    @Test
    void quotedCsvPreservesEmbeddedNewlinesAndEscapedQuotes() {
        byte[] bytes = "Date;Amount;Note\n2026-12-01;10;\"one\n\"\"quoted\"\"\"\n".getBytes(StandardCharsets.UTF_8);
        var inspection = reader.inspect(stream(bytes), "a.csv");
        assertThat(inspection.sheets().getFirst().sample().get(1).get(2).raw()).isEqualTo("one\n\"quoted\"");
        assertThat(reader.read(stream(bytes), "a.csv", new Mapping("csv", 0, 1, 1, Set.of(), null))).hasSize(1);
    }

    @Test
    void worksheetPartCannotBypassPreflightByChangingItsExtension() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var row = book.createSheet().createRow(0);
            row.createCell(0).setCellValue("2026-12-01");
            row.createCell(1).setCellValue(10);
            var output = new ByteArrayOutputStream();
            try (var input = new ZipInputStream(stream(bytes(book))); var zip = new ZipOutputStream(output)) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    String name = entry.getName();
                    byte[] data = input.readAllBytes();
                    if (name.endsWith(".xml") || name.endsWith(".rels")) {
                        data = new String(data, StandardCharsets.UTF_8).replace("sheet1.xml", "sheet1.bin")
                                .getBytes(StandardCharsets.UTF_8);
                    }
                    zip.putNextEntry(new ZipEntry(name.replace("sheet1.xml", "sheet1.bin")));
                    zip.write(data);
                    zip.closeEntry();
                }
            }
            var limits = new ScheduleImportProperties();
            limits.setMaxColumns(1);
            assertIssue(() -> new ScheduleTableReader(limits).inspect(stream(output.toByteArray()), "a.xlsx"),
                    "UNSUPPORTED_WORKSHEET_PART");
        }
    }

    @Test
    void csvLimitFailuresAreNotReinterpretedAsDialectAmbiguity() {
        var limits = new ScheduleImportProperties();
        limits.setMaxRows(1);
        byte[] bytes = "2026-12-01;1\n".repeat(22).getBytes(StandardCharsets.UTF_8);
        assertIssue(() -> new ScheduleTableReader(limits).read(stream(bytes), "a.csv", mapping("csv", null)), "ROW_LIMIT");
    }

    @Test
    void excludedInvalidAmountCannotMasqueradeAsTotal() {
        byte[] bytes = "Date;Amount\n2026-12-01;Total\n2026-12-02;1\n".getBytes(StandardCharsets.UTF_8);
        assertIssue(() -> reader.read(stream(bytes), "a.csv", new Mapping("csv", 0, 1, 1, Set.of(1), null)),
                "INVALID_EXCLUDED_ROW");
    }

    @Test
    void sampleTextIsBoundedWithoutTruncatingImportedRows() {
        byte[] bytes = ("2026-12-01;1;" + "x".repeat(40000) + "\n").getBytes(StandardCharsets.UTF_8);
        var inspection = reader.inspect(stream(bytes), "a.csv");
        assertThat(inspection.issues()).extracting(Issue::code).containsExactly("SAMPLE_TEXT_LIMIT");
        assertThat(inspection.sheets().getFirst().sample()).isEmpty();
        assertThat(reader.read(stream(bytes), "a.csv", mapping("csv", null))).hasSize(1);
    }

    @Test
    void rejectsDoctypeBeforeResolvingExternalEntities() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(("<!DOCTYPE worksheet [<!ENTITY secret SYSTEM 'file:///nonexistent-schedule-test'>]>"
                    + "<worksheet><row><c>&secret;</c></row></worksheet>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertIssue(() -> reader.inspect(stream(output.toByteArray()), "a.xlsx"), "INVALID_WORKBOOK");
    }

    // Reject an exponent before expanding its plain decimal representation.
    @Test
    void rejectsNumericExponentExpansionBeforeAllocatingDecimalText() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var row = book.createSheet().createRow(0);
            row.createCell(0).setCellValue("2026-12-01");
            var amount = row.createCell(1);
            amount.setCellValue(1);
            for (String raw : List.of("1E1000", "1E100000000", "-1E100000000", "1E2147483647",
                    "100E2147483647", "1E2147483648")) {
                amount.getCTCell().setV(raw);
                assertIssue(() -> reader.read(stream(bytes(book)), "a.xlsx", mapping("sheet:0", null)),
                        "AMOUNT_OVERFLOW");
            }
            amount.getCTCell().setV("1E-100000000");
            assertIssue(() -> reader.read(stream(bytes(book)), "a.xlsx", mapping("sheet:0", null)), "AMOUNT_SCALE");
            amount.getCTCell().setV("1.250E2");
            assertThat(reader.read(stream(bytes(book)), "a.xlsx", mapping("sheet:0", null))
                    .getFirst().amountText()).isEqualTo("125");
        }
    }

    // The malformed final token makes a post-allocation Commons CSV width check insufficient.
    @Test
    void unquotedQuoteCannotHideAnOversizedCsvRecordFromPreflight() {
        var limits = new ScheduleImportProperties();
        limits.setMaxColumns(3);
        byte[] bytes = "2026-12-01;1;foo\"\na;b;c;d;\"unterminated".getBytes(StandardCharsets.UTF_8);
        var explicit = new Mapping("csv", 0, 1, 0, Set.of(), null, new CsvOptions("UTF-8", ';'));
        assertIssue(() -> new ScheduleTableReader(limits).read(stream(bytes), "a.csv", explicit), "COLUMN_LIMIT");
        byte[] valid = "2026-12-01;1;foo\"\n2026-12-02;2;ok".getBytes(StandardCharsets.UTF_8);
        assertThat(new ScheduleTableReader(limits).read(stream(valid), "a.csv", explicit)).hasSize(2);
    }

    @Test
    void explicitPrefixMappingConfirmsArbitrarySourceHeaders() {
        for (String header : List.of("Дата платежа;Сумма платежа", "Due Date;Payment Amount")) {
            byte[] bytes = (header + "\n2026-12-01;10\n" + header + "\n2026-12-02;20\n")
                    .getBytes(StandardCharsets.UTF_8);
            var mapping = new Mapping("csv", 0, 1, 1, Set.of(2), null);
            assertThat(normalizer.normalize(reader.read(stream(bytes), "a.csv", mapping), null).rows())
                    .containsExactly(new Row(LocalDate.of(2026, 12, 1), new BigDecimal("10.00")),
                            new Row(LocalDate.of(2026, 12, 2), new BigDecimal("20.00")));
        }
    }

    @Test
    void prefixHeaderConfirmationCannotHideADataRowWithAnInvalidAmount() {
        byte[] bytes = "2026-12-01;broken\n2026-12-02;20\n".getBytes(StandardCharsets.UTF_8);
        assertIssue(() -> reader.read(stream(bytes), "a.csv", new Mapping("csv", 0, 1, 1, Set.of(), null)),
                "INVALID_EXCLUDED_ROW");
    }

    private Mapping mapping(String key, ParseOptions formats) {
        return new Mapping(key, 0, 1, 0, Set.of(), formats);
    }

    private ByteArrayInputStream stream(byte[] bytes) { return new ByteArrayInputStream(bytes); }
    private byte[] bytes(Workbook book) throws Exception {
        var output = new ByteArrayOutputStream();
        book.write(output);
        return output.toByteArray();
    }

    private void assertIssue(Throwing action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(TableReadException.class)
                .satisfies(error -> assertThat(((TableReadException) error).issues()).extracting(Issue::code).contains(code));
    }

    private interface Throwing { void run() throws Exception; }
}
