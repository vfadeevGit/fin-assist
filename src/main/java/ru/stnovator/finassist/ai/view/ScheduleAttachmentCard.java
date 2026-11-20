package ru.stnovator.finassist.ai.view;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.AttachmentType;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.server.HttpStatusCode;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadEvent;
import org.springframework.http.ContentDisposition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import io.jmix.core.FileStorageException;
import io.jmix.core.Messages;
import ru.stnovator.finassist.ai.schedule.ScheduleAttachmentService;
import ru.stnovator.finassist.ai.schedule.ScheduleAttachmentService.SubmittedAttachment;
import ru.stnovator.finassist.ai.schedule.ScheduleImportStore;
import ru.stnovator.finassist.ai.schedule.ScheduleImportStore.AttachmentProcessing;
import ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.Kind;

import java.text.NumberFormat;

import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.UUID;

final class ScheduleAttachmentCard extends Div {
    private static final String GROUP = "ru.stnovator.finassist.ai.view";

    ScheduleAttachmentCard(Messages messages, String fileName, long sizeBytes, Component... actions) {
        addClassName("schedule-attachment-card");
        var label = new Span(fileName);
        label.addClassName("schedule-attachment-name");
        var size = new Span(messages.formatMessage(GROUP, "scheduleImport.attachmentSize", sizeBytes));
        size.addClassName("schedule-attachment-detail");
        add(label, size);
        add(actions);
    }

    static ScheduleAttachmentCard submitted(Messages messages, ScheduleAttachmentService attachments,
                                             ScheduleImportStore store, UUID conversationId, SubmittedAttachment file, boolean showSubmittedAt) {
        var card = new ScheduleAttachmentCard(messages, file.fileName(), file.sizeBytes());
        card.addClassName("schedule-sent-attachment");
        if (file.sourceMessageId() != null) {
            card.getElement().setAttribute("data-source-message-id", file.sourceMessageId().toString());
        }
        if (showSubmittedAt && file.submittedAt() != null) {
            var time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withLocale(UI.getCurrent().getLocale()).format(file.submittedAt());
            card.add(new Span(time));
        }
        if (file.sourceMessageId() == null) {
            card.add(new Span(messages.getMessage(GROUP, "scheduleImport.attachmentSent")));
        } else {
            card.addProcessing(messages, store.attachmentProcessing(conversationId, file.id(), file.sourceMessageId()));
        }
        if (file.available() && file.sourceMessageId() != null) {
            DownloadHandler handler = event -> download(event, attachments, conversationId, file);
            var link = new Anchor(handler, AttachmentType.DOWNLOAD,
                    messages.getMessage(GROUP, "scheduleImport.downloadAttachment"));
            card.add(link);
        } else {
            card.add(new Span(messages.getMessage(GROUP, "scheduleImport.attachmentUnavailable")));
        }
        return card;
    }

    static void download(DownloadEvent event, ScheduleAttachmentService attachments,
                         UUID conversationId, SubmittedAttachment file) throws IOException {
        var response = event.getResponse();
        response.setHeader("Cache-Control", "private, no-store");
        ScheduleAttachmentService.SubmittedDownload original;
        try {
            original = attachments.openSubmitted(file.id(), conversationId, file.sourceMessageId());
        } catch (SecurityException | IllegalStateException | FileStorageException unavailable) {
            response.setStatus(HttpStatusCode.NOT_FOUND.getCode());
            return;
        }
        try (var input = original.stream()) {
            response.setHeader("Content-Disposition", ContentDisposition.attachment()
                    .filename(original.fileName(), StandardCharsets.UTF_8).build().toString());
            event.setContentType("application/octet-stream");
            event.setContentLength(original.sizeBytes());
            try (var output = event.getOutputStream()) {
                input.transferTo(output);
            }
        }
    }

    private void addProcessing(Messages messages, AttachmentProcessing processing) {
        var locale = UI.getCurrent().getLocale();
        var status = switch (processing.stage()) {
            case ATTACHED -> messages.getMessage(GROUP, "scheduleImport.attachmentSent");
            case NEEDS_SHEET -> messages.getMessage(GROUP, "scheduleImport.attachmentNeedsSheet");
            case NEEDS_COLUMNS -> messages.getMessage(GROUP, "scheduleImport.attachmentNeedsColumns");
            case PARSED -> {
                var number = NumberFormat.getNumberInstance(locale);
                number.setMinimumFractionDigits(2);
                number.setMaximumFractionDigits(2);
                yield messages.formatMessage(GROUP, "scheduleImport.attachmentParsed",
                        processing.rowCount(), number.format(processing.total()));
            }
            case ERROR -> messages.formatMessage(GROUP, "scheduleImport.attachmentError",
                    messages.getMessage(GROUP, errorKey(processing.issueCode())));
        };
        var label = new Span(status);
        label.addClassName("schedule-attachment-status");
        add(label);
        if (processing.operationId() != null) {
            getElement().setAttribute("data-operation-id", processing.operationId().toString());
            var time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withLocale(locale).format(processing.requestedAt());
            var kind = messages.getMessage(GROUP, processing.kind() == Kind.PAYMENT
                    ? "scheduleImport.attachmentPayment" : "scheduleImport.attachmentShipment");
            var operation = new Span(messages.formatMessage(GROUP, "scheduleImport.attachmentOperation", kind, time));
            operation.addClassName("schedule-attachment-detail");
            add(operation);
        }
    }

    private static String errorKey(String code) {
        return switch (code) {
            case "FILE_UNAVAILABLE" -> "scheduleImport.attachmentErrorUnavailable";
            case "INVALID_WORKBOOK" -> "scheduleImport.attachmentErrorWorkbook";
            case "CSV_INVALID" -> "scheduleImport.attachmentErrorCsv";
            case "FILE_LIMIT", "ROW_LIMIT", "COLUMN_LIMIT", "CELL_LIMIT" -> "scheduleImport.attachmentErrorLimit";
            case "UNSUPPORTED_FILE_TYPE" -> "scheduleImport.attachmentErrorFileType";
            case "SHEET_REQUIRED" -> "scheduleImport.attachmentErrorSheet";
            case "INVALID_MAPPING", "INVALID_EXCLUDED_ROW" -> "scheduleImport.attachmentErrorMapping";
            case "INVALID_PARSE_OPTIONS" -> "scheduleImport.attachmentErrorFormat";
            case "NO_DATA_ROWS" -> "scheduleImport.attachmentErrorEmpty";
            case "INVALID_DATE" -> "scheduleImport.attachmentErrorDate";
            case "INVALID_AMOUNT" -> "scheduleImport.attachmentErrorAmount";
            case "NON_POSITIVE_AMOUNT" -> "scheduleImport.attachmentErrorPositive";
            case "AMOUNT_SCALE" -> "scheduleImport.attachmentErrorScale";
            case "AMOUNT_OVERFLOW" -> "scheduleImport.attachmentErrorOverflow";
            case "FORMULA_REQUIRES_CORRECTION" -> "scheduleImport.attachmentErrorFormula";
            case "CELL_REQUIRES_CORRECTION" -> "scheduleImport.attachmentErrorCell";
            default -> "scheduleImport.attachmentErrorRead";
        };
    }
}
