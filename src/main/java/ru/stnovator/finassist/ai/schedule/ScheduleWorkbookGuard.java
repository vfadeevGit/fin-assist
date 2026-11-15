package ru.stnovator.finassist.ai.schedule;

import org.apache.poi.hssf.eventusermodel.HSSFEventFactory;
import org.apache.poi.hssf.eventusermodel.HSSFRequest;
import org.apache.poi.hssf.record.BoundSheetRecord;
import org.apache.poi.hssf.record.CellValueRecordInterface;
import org.apache.poi.hssf.record.MulBlankRecord;
import org.apache.poi.hssf.record.RowRecord;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipInputStream;

import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.Issue;
import static ru.stnovator.finassist.ai.schedule.ScheduleTableTypes.TableReadException;

/** Streaming structural limits checked before POI builds workbook object graphs. */
final class ScheduleWorkbookGuard {
    private static final int HEADER_ALLOWANCE = 20;
    private final ScheduleImportProperties limits;

    ScheduleWorkbookGuard(ScheduleImportProperties limits) { this.limits = limits; }

    void check(byte[] bytes, boolean xlsx) throws Exception {
        if (xlsx) preflightZip(bytes); else preflightXls(bytes);
    }

    /** Streams all archive contents and XML before POI can allocate its workbook/object graph. */
    private void preflightZip(byte[] bytes) throws Exception {
        long[] inflated = {0};
        int[] cells = {0};
        int[] sheetCount = {0};
        var names = new HashSet<String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!names.add(entry.getName()) || names.size() > 1024) throw failure("file", "ARCHIVE_ENTRY_LIMIT");
                if (entry.getName().startsWith("xl/externalLinks/") || entry.getName().endsWith("vbaProject.bin")) {
                    throw failure("file", "UNSUPPORTED_ACTIVE_CONTENT");
                }
                if (entry.getSize() > limits.getMaxInflatedBytes()) throw failure("file", "DECOMPRESSION_LIMIT");
                var bounded = new FilterInputStream(zip) {
                    @Override public int read() throws IOException {
                        int value = in.read();
                        if (value != -1) check(1);
                        return value;
                    }
                    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                        int count = in.read(buffer, offset, length);
                        if (count > 0) check(count);
                        return count;
                    }
                    private void check(int count) {
                        inflated[0] += count;
                        if (inflated[0] > limits.getMaxInflatedBytes()) throw failure("file", "DECOMPRESSION_LIMIT");
                    }
                    @Override public void close() { /* ZipInputStream owns each entry. */ }
                };
                if (entry.getName().endsWith(".xml") || entry.getName().endsWith(".rels")) {
                    var factory = SAXParserFactory.newDefaultInstance();
                    factory.setNamespaceAware(true);
                    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                    var parser = factory.newSAXParser();
                    parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                    parser.parse(new InputSource(bounded), new DefaultHandler() {
                        private boolean worksheet;
                        private int depth;
                        private int rows;
                        private int columns;
                        @Override public void startElement(String uri, String local, String q, Attributes attrs) {
                            if (++depth > 64) throw failure("file", "XML_DEPTH_LIMIT");
                            if (local.equals("Relationship")) {
                                String type = attrs.getValue("Type");
                                String target = attrs.getValue("Target");
                                if (type != null && type.endsWith("/worksheet")
                                        && (target == null || !target.endsWith(".xml"))) {
                                    throw failure("file", "UNSUPPORTED_WORKSHEET_PART");
                                }
                            }
                            if (local.equals("worksheet")) worksheet = true;
                            if (local.equals("sheet") && ++sheetCount[0] > limits.getMaxSheets()) {
                                throw failure("file", "SHEET_LIMIT");
                            }
                            if (!worksheet) return;
                            if (local.equals("row")) {
                                if (++rows > limits.getMaxRows() + HEADER_ALLOWANCE) throw failure("file", "ROW_LIMIT");
                                columns = 0;
                                String number = attrs.getValue("r");
                                if (number != null && Long.parseLong(number) > limits.getMaxRows() + HEADER_ALLOWANCE) {
                                    throw failure("file", "ROW_LIMIT");
                                }
                            }
                            if (local.equals("c")) {
                                if (++cells[0] > limits.getMaxCells()) throw failure("file", "CELL_LIMIT");
                                String reference = attrs.getValue("r");
                                int column = 0;
                                if (reference != null) {
                                    for (int n = 0; n < reference.length() && Character.isLetter(reference.charAt(n)); n++) {
                                        column = column * 26 + reference.charAt(n) - 'A' + 1;
                                        if (column > limits.getMaxColumns()) throw failure("file", "COLUMN_LIMIT");
                                    }
                                }
                                if (++columns > limits.getMaxColumns()) throw failure("file", "COLUMN_LIMIT");
                            }
                        }
                        @Override public void endElement(String uri, String local, String q) { depth--; }
                        @Override public void error(org.xml.sax.SAXParseException ex) throws SAXException { throw ex; }
                        @Override public void fatalError(org.xml.sax.SAXParseException ex) throws SAXException { throw ex; }
                    });
                }
                byte[] drain = new byte[8192];
                while (bounded.read(drain) != -1) { /* Check unused/binary parts too. */ }
                zip.closeEntry();
            }
        }
        if (names.isEmpty()) throw failure("file", "INVALID_WORKBOOK");
    }

    /** The BIFF event pass rejects expansion into many cells before the HSSF user model is built. */
    private void preflightXls(byte[] bytes) throws IOException {
        int[] sheets = {0};
        int[] cells = {0};
        var request = new HSSFRequest();
        request.addListenerForAllRecords(record -> {
            if (record instanceof BoundSheetRecord && ++sheets[0] > limits.getMaxSheets()) throw failure("file", "SHEET_LIMIT");
            if (record instanceof RowRecord row && row.getRowNumber() >= limits.getMaxRows() + HEADER_ALLOWANCE) {
                throw failure("file", "ROW_LIMIT");
            }
            if (record instanceof CellValueRecordInterface cell) {
                checkCell(cell.getRow(), cell.getColumn(), ++cells[0]);
            } else if (record instanceof MulBlankRecord blank) {
                cells[0] += blank.getNumColumns();
                checkCell(blank.getRow(), blank.getLastColumn(), cells[0]);
            }
        });
        try (var fs = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            new HSSFEventFactory().processWorkbookEvents(request, fs);
        }
    }

    private void checkCell(int row, int column, int cells) {
        if (row >= limits.getMaxRows() + HEADER_ALLOWANCE) throw failure("file", "ROW_LIMIT");
        if (column >= limits.getMaxColumns()) throw failure("file", "COLUMN_LIMIT");
        if (cells > limits.getMaxCells()) throw failure("file", "CELL_LIMIT");
    }

    private TableReadException failure(String source, String code) {
        return new TableReadException(List.of(new Issue(source, code)));
    }
}
