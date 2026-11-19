package ru.stnovator.finassist.ai.view;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import ru.stnovator.finassist.ai.EntityLinksTool;
import ru.stnovator.finassist.ai.schedule.ScheduleImportStore;
import io.jmix.aitoolsflowui.view.chat.renderer.TimelineItemRenderer;
import io.jmix.aitoolsflowui.view.chat.timeline.TimelineItem;
import io.jmix.aitoolsflowui.view.chat.timeline.TimelineItemType;
import io.jmix.flowui.fragment.FragmentDescriptor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import ru.stnovator.finassist.ai.schedule.ScheduleAttachmentService;

import java.util.ArrayList;
import java.util.List;

/** Extends native rows with owned metadata only; message text and native rendering stay intact. */
@FragmentDescriptor("schedule-timeline-item-renderer.xml")
public class ScheduleTimelineItemRenderer extends TimelineItemRenderer {
    @Autowired
    private ScheduleAttachmentService attachments;
    @Autowired
    private ScheduleImportStore store;
    @Autowired
    private EntityLinksTool links;
    private final List<Component> decorations = new ArrayList<>();

    @Override
    public void setItem(@Nullable TimelineItem item) {
        decorations.forEach(badge -> getContent().remove(badge));
        decorations.clear();
        super.setItem(item);
        if (item == null || item.getMessage() == null || item.getMessage().getConversation() == null) return;
        var message = item.getMessage();
        if (item.getType() == TimelineItemType.ASSISTANT) {
            store.receiptForMessage(message.getConversation().getId(), message.getId()).ifPresent(receipt -> {
                var link = links.getEntityLinks(receipt.entityName(), List.of(receipt.resultId().toString()))
                        .stream().filter(EntityLinksTool.Link::available).findFirst();
                String group = "ru.stnovator.finassist.ai.view";
                Component destination = link.<Component>map(l -> new Anchor(l.url(), messages.getMessage(group, "scheduleImport.openResult")))
                        .orElseGet(() -> new Span(messages.getMessage(group, "scheduleImport.resultUnavailable")));
                decorations.add(destination);
                getContent().add(destination);
            });
            return;
        }
        if (item.getType() != TimelineItemType.USER) return;
        for (var attachment : attachments.forMessage(message.getConversation().getId(), message.getId())) {
            String group = "ru.stnovator.finassist.ai.view";
            String status = messages.getMessage(group, attachment.available()
                    ? "scheduleImport.attachmentSent" : "scheduleImport.attachmentUnavailable");
            var badge = new Span(messages.formatMessage(group, "scheduleImport.sentAttachment",
                    attachment.fileName(), attachment.sizeBytes(), status));
            badge.addClassName("schedule-sent-attachment");
            badge.getElement().setAttribute("data-source-message-id", message.getId().toString());
            decorations.add(badge);
            getContent().add(badge);
        }
    }
}
