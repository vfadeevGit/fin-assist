package ru.stnovator.finassist.ai.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Per-import limits; the reader also validates them before allocating input buffers. */
@Component
@ConfigurationProperties(prefix = "finassist.schedule-import")
public class ScheduleImportProperties {
    private java.time.Duration draftTtl = java.time.Duration.ofHours(24);
    private java.time.Duration fileRetention = java.time.Duration.ofHours(24);
    public java.time.Duration getDraftTtl() { return draftTtl; }
    public void setDraftTtl(java.time.Duration value) {
        if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException("draftTtl must be positive");
        draftTtl = value;
    }
    public java.time.Duration getFileRetention() { return fileRetention; }
    public void setFileRetention(java.time.Duration value) {
        if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException("fileRetention must be positive");
        fileRetention = value;
    }
    private long maxFileBytes = 25L * 1024 * 1024;
    private long maxInflatedBytes = 64L * 1024 * 1024;
    private int maxRows = 10000;
    private int maxColumns = 100;
    private int maxSheets = 20;
    private int maxCells = 200000;
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long value) { maxFileBytes = value; }
    public long getMaxInflatedBytes() { return maxInflatedBytes; }
    public void setMaxInflatedBytes(long value) { maxInflatedBytes = value; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int value) { maxRows = value; }
    public int getMaxColumns() { return maxColumns; }
    public void setMaxColumns(int value) { maxColumns = value; }
    public int getMaxSheets() { return maxSheets; }
    public void setMaxSheets(int value) { maxSheets = value; }
    public int getMaxCells() { return maxCells; }
    public void setMaxCells(int value) { maxCells = value; }
}
