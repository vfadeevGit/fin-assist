package ru.stnovator.finassist.ai.view;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
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
    private boolean uploading;
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
        attachmentUpload.addUploadStartedListener(e -> {
            uploading = true;
            super.setInputEnabled(false);
        });
        attachmentUpload.addUploadFailedListener(e -> {
            uploading = false;
            setInputEnabled(true);
            error();
        });
        attachmentUpload.addUploadFinishedListener(e -> {
            uploading = false;
            setInputEnabled(true);
        });
    }

    private String text(String key) {
        return messages.getMessage("ru.stnovator.finassist.ai.view/scheduleImport." + key);
    }

    public void bindConversation(@Nullable AiConversation conversation) {
        UUID old = attachmentConversation == null ? null : attachmentConversation.getId();
        UUID next = conversation == null ? null : conversation.getId();
        if (!Objects.equals(old, next)) removePendingAttachment();
        attachmentConversation = conversation;
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
        return uploading;
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
        try {
            if (info == null || pending != null) throw new IllegalStateException();
            // Native conversation creation is required before stage(), including on the hub.
            if (attachmentConversation == null) attachmentConversation = conversations.create();
            try (var input = new FileInputStream(info.getFile())) {
                UUID id = attachments.stage(attachmentConversation.getId(), event.getFileName(), input);
                pending = new Pending(id, event.getFileName(), info.getFile().length());
            }
            renderPending();
        }
        catch (IOException | RuntimeException failure) {
            error();
        } finally {
            if (info != null) temporaryStorage.deleteFile(info.getId());
            attachmentUpload.clearFileList();
            uploading = false;
            setInputEnabled(true);
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
        super.setInputEnabled(enabled && !uploading);
        if (addMenu != null) addMenu.setEnabled(enabled && !uploading && pending == null);
    }

    @Override
    protected void submit() {
        if (!uploading) super.submit();
    }

    private void error() {
        notifications.create(text("uploadError")).withType(Notifications.Type.ERROR).show();
    }
}
