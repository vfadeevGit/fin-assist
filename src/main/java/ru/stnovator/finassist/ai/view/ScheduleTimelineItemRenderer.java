package ru.stnovator.finassist.ai.view;

import com.vaadin.flow.component.html.Span;
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
    private final List<Span> attachmentBadges = new ArrayList<>();

    @Override
    public void setItem(@Nullable TimelineItem item) {
        attachmentBadges.forEach(badge -> getContent().remove(badge));
        attachmentBadges.clear();
        super.setItem(item);
        if (item == null || item.getType() != TimelineItemType.USER || item.getMessage() == null
                || item.getMessage().getConversation() == null) return;
        var message = item.getMessage();
        for (var attachment : attachments.forMessage(message.getConversation().getId(), message.getId())) {
            String group = "ru.stnovator.finassist.ai.view";
            String status = messages.getMessage(group, attachment.available()
                    ? "scheduleImport.attachmentSent" : "scheduleImport.attachmentUnavailable");
            var badge = new Span(messages.formatMessage(group, "scheduleImport.sentAttachment",
                    attachment.fileName(), attachment.sizeBytes(), status));
            badge.addClassName("schedule-sent-attachment");
            badge.getElement().setAttribute("data-source-message-id", message.getId().toString());
            attachmentBadges.add(badge);
            getContent().add(badge);
        }
    }
}
