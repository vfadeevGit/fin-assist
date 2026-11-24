package ru.stnovator.finassist.ai.schedule;

import ru.stnovator.finassist.service.ScheduleComparison;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

/** A server-built, complete preview; revision identifies the only confirmable draft. */
public record ScheduleImportPreview(UUID operationId, long revision, String contractCaption, String projectCaption,
                                    Kind kind, Mode mode, String number, LocalDate effectiveDate, List<Row> rows,
                                    ScheduleComparison comparison, BigDecimal total, String fingerprint, boolean noChanges, UUID existingAddendumId) {
    public ScheduleImportPreview(UUID operationId, long revision, String contractCaption, String projectCaption,
                                 Kind kind, Mode mode, String number, LocalDate effectiveDate, List<Row> rows,
                                 ScheduleComparison comparison, BigDecimal total, String fingerprint, boolean noChanges) {
        this(operationId,revision,contractCaption,projectCaption,kind,mode,number,effectiveDate,rows,
                comparison,total,fingerprint,noChanges,null);
    }
    public ScheduleImportPreview { rows = List.copyOf(rows); }
}
