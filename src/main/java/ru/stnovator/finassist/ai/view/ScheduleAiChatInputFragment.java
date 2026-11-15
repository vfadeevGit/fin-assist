package ru.stnovator.finassist.ai.view;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.FailedEvent;
import com.vaadin.flow.component.upload.StartedEvent;
import io.jmix.aitoolsflowui.model.AiConversation;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.aitoolsflowui.view.input.AiChatInputFragment;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.component.upload.UploadSucceededEvent;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.upload.TemporaryStorage;
import io.jmix.flowui.view.*;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import ru.stnovator.finassist.ai.schedule.ScheduleAttachmentService;
import ru.stnovator.finassist.ai.schedule.ScheduleImportProperties;

import java.io.*;
import java.util.*;

@FragmentDescriptor("schedule-ai-chat-input-fragment.xml")
public class ScheduleAiChatInputFragment extends AiChatInputFragment {
    public record Pending(UUID id, String fileName, long sizeBytes) {
    }

    @Autowired
    private ScheduleAttachmentService attachments;
    @Autowired
    private AiConversationService conversations;
    @Autowired
    private TemporaryStorage temporaryStorage;
    @Autowired
    private ScheduleImportProperties properties;
    @Autowired
    private Messages messages;
    @Autowired
    private Notifications notifications;
    @ViewComponent
    private JmixUpload<TemporaryStorage.FileInfo> attachmentUpload;
    @ViewComponent
    private HorizontalLayout inputBar;
    @ViewComponent
    private VerticalLayout pendingAttachment;
    private AiConversation attachmentConversation;
    private Pending pending;
    private record UploadAttempt(UUID conversationId, long generation, String fileName) {}
    private UploadAttempt activeUpload;
    private long bindingGeneration;
    private boolean inputEnabled = true;
    private MenuBar addMenu;

    @Subscribe
    public void onScheduleReady(ReadyEvent event) {
        attachmentUpload.setMaxFileSize((int) Math.min(Integer.MAX_VALUE, properties.getMaxFileBytes()));
        attachmentUpload.setUploadButton(new Span(text("upload")));
        addMenu = new MenuBar();
        addMenu.setId("scheduleAddAttachment");
        addMenu.setMinWidth("5em");
        var add = addMenu.addItem(VaadinIcon.PLUS.create());
        add.setAriaLabel(text("attach"));
        add.getSubMenu().addItem(attachmentUpload, e -> {
        });
        inputBar.addComponentAsFirst(addMenu);
        attachmentUpload.addUploadStartedListener(this::onUploadStarted);
        attachmentUpload.addUploadFailedListener(this::onUploadFailed);
        // Finished means the bytes transferred. The pinned handler validates and delivers Success later.
    }

    private String text(String key) {
        return messages.getMessage("ru.stnovator.finassist.ai.view/scheduleImport." + key);
    }

    public void bindConversation(@Nullable AiConversation conversation) {
        UUID old = attachmentConversation == null ? null : attachmentConversation.getId();
        UUID next = conversation == null ? null : conversation.getId();
        if (!Objects.equals(old, next)) {
            bindingGeneration++;
            removePendingAttachment();
        }
        attachmentConversation = conversation;
        refreshInputAvailability();
    }

    public AiConversation getAttachmentConversation() {
        return attachmentConversation;
    }

    public Pending getPendingAttachment() {
        return pending;
    }

    public JmixUpload<TemporaryStorage.FileInfo> getAttachmentUpload() {
        return attachmentUpload;
    }

    public boolean isUploading() {
        return activeUpload != null;
    }

    private void onUploadStarted(StartedEvent event) {
        if (activeUpload != null || !inputEnabled || pending != null) return;
        try {
            // Snapshot the native conversation before transfer completion, including on the hub.
            if (attachmentConversation == null) attachmentConversation = conversations.create();
            activeUpload = new UploadAttempt(attachmentConversation.getId(), bindingGeneration, event.getFileName());
            refreshInputAvailability();
        } catch (RuntimeException failure) {
            error();
        }
    }

    private boolean isCurrent(UploadAttempt attempt) {
        return attempt != null && attempt == activeUpload && attempt.generation() == bindingGeneration
                && attachmentConversation != null && attempt.conversationId().equals(attachmentConversation.getId());
    }

    private void completeUpload(UploadAttempt attempt) {
        if (attempt != null && attempt == activeUpload) activeUpload = null;
        refreshInputAvailability();
    }

    private void onUploadFailed(FailedEvent event) {
        var attempt = activeUpload;
        if (attempt == null || !attempt.fileName().equals(event.getFileName())) return;
        if (isCurrent(attempt)) error();
        completeUpload(attempt);
    }

    public Pending takePendingAttachment() {
        var value = pending;
        pending = null;
        renderPending();
        return value;
    }

    public void acceptPendingAttachment(Pending value) {
        pending = value;
        renderPending();
    }

    public void removePendingAttachment() {
        if (pending != null) attachments.remove(pending.id());
        pending = null;
        renderPending();
    }

    @Subscribe("attachmentUpload")
    public void onAttachmentUploadSucceeded(UploadSucceededEvent<TemporaryStorage.FileInfo> event) {
        var info = event.getData();
        var attempt = activeUpload;
        try {
            if (!isCurrent(attempt) || !attempt.fileName().equals(event.getFileName())) return;
            if (info == null || pending != null) throw new IllegalStateException();
            try (var input = new FileInputStream(info.getFile())) {
                UUID id = attachments.stage(attempt.conversationId(), event.getFileName(), input);
                if (!isCurrent(attempt)) {
                    attachments.remove(id);
                    return;
                }
                pending = new Pending(id, event.getFileName(), info.getFile().length());
            }
            renderPending();
        }
        catch (IOException | RuntimeException failure) {
            if (isCurrent(attempt)) error();
        } finally {
            try {
                if (info != null) temporaryStorage.deleteFile(info.getId());
            } finally {
                attachmentUpload.clearFileList();
                completeUpload(attempt);
            }
        }
    }

    private void renderPending() {
        if (pendingAttachment == null) return;
        pendingAttachment.removeAll();
        pendingAttachment.setVisible(pending != null);
        if (pending != null) {
            var remove = new Button(text("remove"), e -> removePendingAttachment());
            remove.setId("scheduleRemoveAttachment");
            pendingAttachment.add(new HorizontalLayout(new Span(pending.fileName() + " (" + pending.sizeBytes() + " " + text("bytes") + ")"), remove));
        }
        if (addMenu != null) addMenu.setEnabled(pending == null && isInputEnabled());
    }

    @Override
    public void setInputEnabled(boolean enabled) {
        inputEnabled = enabled;
        refreshInputAvailability();
    }

    private void refreshInputAvailability() {
        super.setInputEnabled(inputEnabled && !isUploading());
        if (addMenu != null) addMenu.setEnabled(inputEnabled && !isUploading() && pending == null);
    }

    @Override
    protected void submit() {
        if (!isUploading()) super.submit();
    }

    private void error() {
        notifications.create(text("uploadError")).withType(Notifications.Type.ERROR).show();
    }
}
