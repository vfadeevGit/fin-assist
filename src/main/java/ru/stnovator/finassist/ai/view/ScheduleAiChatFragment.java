package ru.stnovator.finassist.ai.view;

import com.vaadin.flow.component.button.Button;
import io.jmix.aitoolsflowui.model.*;
import io.jmix.aitoolsflowui.view.chat.AiChatFragment;
import io.jmix.core.Messages;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.view.Subscribe;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import ru.stnovator.finassist.ai.EntityLinksTool;
import ru.stnovator.finassist.ai.schedule.*;

import java.util.Objects;

@FragmentDescriptor("schedule-ai-chat-fragment.xml")
public class ScheduleAiChatFragment extends AiChatFragment {
    @Autowired
    private ScheduleAttachmentService attachments;
    @Autowired
    private ScheduleImportStore store;
    @Autowired
    private ScheduleImportWorkflow workflow;
    @Autowired
    private ScheduleImportTool tool;
    @Autowired
    private ScheduleImportCommitService commit;
    @Autowired
    private EntityLinksTool links;
    @Autowired
    private Messages messages;
    private ScheduleImportPanel importPanel;
    private AiChatMessage retryMessage;
    private Button retryButton;

    @Subscribe
    public void onScheduleReady(ReadyEvent event) {
        messageBundle.setMessageGroup("io.jmix.aitoolsflowui.view.chat");
        importPanel = new ScheduleImportPanel(store, workflow, tool, commit, links, messages, attachments, prompt -> {
            var input = (ScheduleAiChatInputFragment) composerFragment;
            input.focus();
            notifications.create(prompt).show();
        });
        composerContainer.addComponentAsFirst(importPanel);
        retryButton = new Button(messages.getMessage("ru.stnovator.finassist.ai.view/scheduleImport.retry"), e -> retry());
        retryButton.setId("scheduleRetryMessage");
        retryButton.setVisible(false);
        composerContainer.add(retryButton);
        restoreImport();
    }

    @Override
    public void setConversation(@Nullable AiConversation value) {
        boolean changed = !Objects.equals(conversation == null ? null : conversation.getId(), value == null ? null : value.getId());
        if (changed) {
            retryMessage = null;
            if (retryButton != null) retryButton.setVisible(false);
            if (importPanel != null) importPanel.clear();
        }
        ((ScheduleAiChatInputFragment) composerFragment).bindConversation(value);
        super.setConversation(value);
        restoreImport();
    }

    private void restoreImport() {
        if (importPanel != null) {
            importPanel.setEnabled(true);
            importPanel.bind(conversation == null ? null : conversation.getId());
        }
    }

    public void acceptPendingAttachment(ScheduleAiChatInputFragment.Pending value) {
        ((ScheduleAiChatInputFragment) composerFragment).acceptPendingAttachment(value);
    }

    @Override
    public void sendMessage(@Nullable String message) {
        if (((ScheduleAiChatInputFragment) composerFragment).isUploading()) return;
        super.sendMessage(message);
    }

    @Override
    protected void processUserMessage(AiChatMessage actualUserMessage) {
        retryMessage = actualUserMessage;
        if (importPanel != null) {
            importPanel.clear();
            importPanel.setEnabled(false);
        }
        try {
            var input = (ScheduleAiChatInputFragment) composerFragment;
            var pending = input.getPendingAttachment();
            if (pending != null) {
                attachments.bindToMessage(pending.id(), actualUserMessage.getId());
                input.takePendingAttachment();
            }
            super.processUserMessage(actualUserMessage);
        }
        catch (RuntimeException failure) {
            showAssistantProcessingError();
        }
    }

    @Override
    protected void handleAssistantResponseDone(@Nullable AiChatMessage finalMessage) {
        retryMessage = null;
        if (retryButton != null) retryButton.setVisible(false);
        super.handleAssistantResponseDone(finalMessage);
    }

    @Override
    protected void showAssistantProcessingError() {
        super.showAssistantProcessingError();
        setMessageInputEnabled(true);
        if (retryButton != null) retryButton.setVisible(retryMessage != null);
        restoreImport();
    }

    private void retry() {
        if (retryMessage == null || awaitingResponse) return;
        if (conversation == null || retryMessage.getConversation() == null || !Objects.equals(retryMessage.getConversation().getId(), conversation.getId()))
            return;
        retryButton.setVisible(false);
        setMessageInputEnabled(false);
        awaitingResponse = true;
        showThinkingIndicator();
        processUserMessage(retryMessage);
    }

    public ScheduleImportPanel getImportPanel() {
        return importPanel;
    }

    public Button getRetryButton() {
        return retryButton;
    }
}
