package ru.stnovator.finassist.ai.view;

import com.vaadin.flow.component.button.Button;
import io.jmix.aitoolsflowui.model.*;
import io.jmix.aitoolsflowui.view.chat.AiChatFragment;
import io.jmix.aitoolsflowui.view.chat.timeline.TimelineItemType;
import io.jmix.core.Messages;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.view.Subscribe;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import ru.stnovator.finassist.ai.schedule.*;

import java.util.Objects;

@FragmentDescriptor("schedule-ai-chat-fragment.xml")
public class ScheduleAiChatFragment extends AiChatFragment {
    @Autowired
    private ScheduleAttachmentService attachments;
    @Autowired
    private ScheduleConversationConfirmationService confirmations;
    @Autowired
    private Messages messages;
    private ScheduleConversationConfirmationService.Proposal displayed;
    private ScheduleConversationConfirmationService.Proposal submittedObservation;
    private ScheduleConversationConfirmationService.Proposal retryObservation;
    private AiChatMessage retryMessage;
    private ScheduleAiChatInputFragment.Pending retryAttachment;
    private boolean retryAttachmentBound;
    private Button retryButton;

    @Subscribe
    public void onScheduleReady(ReadyEvent event) {
        messageBundle.setMessageGroup("io.jmix.aitoolsflowui.view.chat");
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
            retryAttachment = null;
            if (retryButton != null) retryButton.setVisible(false);
            displayed = null;
            submittedObservation = null;
            retryObservation = null;
        }
        ((ScheduleAiChatInputFragment) composerFragment).bindConversation(value);
        super.setConversation(value);
        restoreImport();
    }

    private void restoreImport() {
        displayed = conversation == null ? null : confirmations.pendingProposal(conversation.getId())
                .filter(p -> timelineItemsDc.getItems().stream()
                        .filter(item -> item.getType() == TimelineItemType.ASSISTANT && item.getMessage() != null)
                        .anyMatch(item -> p.matchesDisplayed(item.getMessage().getId(), item.getMessage().getContent())))
                .orElse(null);
    }

    public void acceptPendingAttachment(ScheduleAiChatInputFragment.Pending value) {
        ((ScheduleAiChatInputFragment) composerFragment).acceptPendingAttachment(value);
    }

    @Override
    public void sendMessage(@Nullable String message) {
        if (((ScheduleAiChatInputFragment) composerFragment).isUploading()) return;
        submittedObservation = displayed;
        super.sendMessage(message);
    }

    @Override
    protected void processUserMessage(AiChatMessage actualUserMessage) {
        retryObservation = submittedObservation;
        retryMessage = actualUserMessage;
        retryAttachment = ((ScheduleAiChatInputFragment) composerFragment).takePendingAttachment();
        retryAttachmentBound = retryAttachment == null;
        processSubmittedMessage(actualUserMessage);
    }

    private void processSubmittedMessage(AiChatMessage actualUserMessage) {
        if (retryButton != null) retryButton.setVisible(false);
        try {
            var observed = retryObservation;
            confirmations.captureObservation(actualUserMessage.getConversation().getId(), actualUserMessage.getId(),
                    observed == null ? null : observed.messageId(), observed == null ? null : observed.revision());
            if (!retryAttachmentBound && retryAttachment != null) {
                attachments.bindToMessage(retryAttachment.id(), actualUserMessage.getId());
                retryAttachmentBound = true;
                timelineItemsDc.getItems().stream()
                        .filter(item -> item.getType() == TimelineItemType.USER
                                && item.getMessage() != null && actualUserMessage.getId().equals(item.getMessage().getId()))
                        .findFirst().ifPresent(this::refreshTimelineItem);
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
        retryAttachment = null;
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
        if (retryMessage == null || awaitingResponse || ((ScheduleAiChatInputFragment) composerFragment).isUploading()) return;
        if (conversation == null || retryMessage.getConversation() == null || !Objects.equals(retryMessage.getConversation().getId(), conversation.getId()))
            return;
        retryButton.setVisible(false);
        setMessageInputEnabled(false);
        awaitingResponse = true;
        showThinkingIndicator();
        processSubmittedMessage(retryMessage);
    }

    public Button getRetryButton() {
        return retryButton;
    }
}
