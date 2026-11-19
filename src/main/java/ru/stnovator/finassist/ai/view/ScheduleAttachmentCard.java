package ru.stnovator.finassist.ai.view;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.AttachmentType;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.server.HttpStatusCode;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadResponse;
import io.jmix.core.FileStorageException;
import io.jmix.core.Messages;
import ru.stnovator.finassist.ai.schedule.ScheduleAttachmentService;
import ru.stnovator.finassist.ai.schedule.ScheduleAttachmentService.SubmittedAttachment;

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
                                             UUID conversationId, SubmittedAttachment file, boolean showSubmittedAt) {
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
        card.add(new Span(messages.getMessage(GROUP, "scheduleImport.attachmentSent")));
        if (file.available() && file.sourceMessageId() != null) {
            var handler = DownloadHandler.fromInputStream(event -> {
                event.getResponse().setHeader("Cache-Control", "private, no-store");
                try {
                    var original = attachments.openSubmitted(file.id(), conversationId, file.sourceMessageId());
                    return new DownloadResponse(original.stream(), original.fileName(),
                            "application/octet-stream", original.sizeBytes());
                } catch (SecurityException | IllegalStateException | FileStorageException unavailable) {
                    return DownloadResponse.error(HttpStatusCode.NOT_FOUND);
                }
            });
            var link = new Anchor(handler, AttachmentType.DOWNLOAD,
                    messages.getMessage(GROUP, "scheduleImport.downloadAttachment"));
            card.add(link);
        } else {
            card.add(new Span(messages.getMessage(GROUP, "scheduleImport.attachmentUnavailable")));
        }
        return card;
    }
}
