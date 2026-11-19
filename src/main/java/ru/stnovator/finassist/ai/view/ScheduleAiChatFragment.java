package ru.stnovator.finassist.ai.view;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
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
import java.util.UUID;

@FragmentDescriptor("schedule-ai-chat-fragment.xml")
public class ScheduleAiChatFragment extends AiChatFragment {
    @Autowired
    private ScheduleAttachmentService attachments;
    @Autowired
    private ScheduleImportStore store;
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
    private Details conversationFiles;
    private Div conversationFileList;
    private Button moreFiles;

    @Subscribe
    public void onScheduleReady(ReadyEvent event) {
        messageBundle.setMessageGroup("io.jmix.aitoolsflowui.view.chat");
        retryButton = new Button(messages.getMessage("ru.stnovator.finassist.ai.view/scheduleImport.retry"), e -> retry());
        retryButton.setId("scheduleRetryMessage");
        retryButton.setVisible(false);
        composerContainer.add(retryButton);
        conversationFileList = new Div();
        conversationFileList.addClassName("schedule-conversation-file-list");
        conversationFiles = new Details("", conversationFileList);
        conversationFiles.setId("scheduleConversationFiles");
        conversationFiles.setWidthFull();
        var content = (VerticalLayout) timelineList.getParent().orElseThrow();
        content.addComponentAsFirst(conversationFiles);
        restoreImport();
        refreshConversationFiles();
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
            if (conversationFiles != null) conversationFiles.setOpened(false);
        }
        ((ScheduleAiChatInputFragment) composerFragment).bindConversation(value);
        super.setConversation(value);
        restoreImport();
        refreshConversationFiles();
    }

    private void refreshConversationFiles() {
        if (conversationFiles == null) return;
        conversationFileList.removeAll();
        moreFiles = null;
        conversationFiles.setVisible(conversation != null);
        if (conversation != null) appendConversationFiles(conversation.getId(), 0);
    }

    private void appendConversationFiles(UUID conversationId, int offset) {
        if (conversation == null || !conversationId.equals(conversation.getId())) return;
        if (moreFiles != null) conversationFileList.remove(moreFiles);
        moreFiles = null;
        String group = "ru.stnovator.finassist.ai.view";
        ScheduleAttachmentService.SubmittedPage page;
        try {
            page = attachments.listSubmitted(conversationId, offset);
        } catch (SecurityException unavailable) {
            conversationFileList.removeAll();
            conversationFiles.setVisible(false);
            return;
        }
        conversationFiles.setSummaryText(messages.formatMessage(group, "scheduleImport.conversationFiles", page.totalCount()));
        page.files().forEach(file -> conversationFileList.add(
                ScheduleAttachmentCard.submitted(messages, attachments, store, conversationId, file, true)));
        if (page.totalCount() == 0) {
            conversationFileList.add(new Span(messages.getMessage(group, "scheduleImport.noConversationFiles")));
        }
        if (page.nextOffset() != null) {
            moreFiles = new Button(messages.getMessage(group, "scheduleImport.moreConversationFiles"),
                    e -> appendConversationFiles(conversationId, page.nextOffset()));
            conversationFileList.add(moreFiles);
        }
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
        boolean newlyBound = false;
        try {
            var observed = retryObservation;
            confirmations.captureObservation(actualUserMessage.getConversation().getId(), actualUserMessage.getId(),
                    observed == null ? null : observed.messageId(), observed == null ? null : observed.revision());
            if (!retryAttachmentBound && retryAttachment != null) {
                attachments.bindToMessage(retryAttachment.id(), actualUserMessage.getId());
                retryAttachmentBound = true;
                newlyBound = true;
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
        if (newlyBound) refreshConversationFiles();
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
