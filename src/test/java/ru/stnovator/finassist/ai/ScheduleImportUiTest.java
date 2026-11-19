package ru.stnovator.finassist.ai;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.upload.StartedEvent;
import com.vaadin.flow.component.upload.FinishedEvent;
import com.vaadin.flow.component.upload.FailedEvent;
import io.jmix.aitoolsflowui.model.AiChatMessageType;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.aitoolsflowui.service.AiChatMessageService;
import io.jmix.aitoolsflowui.view.chat.AiChatView;
import io.jmix.aitoolsflowui.view.chat.support.AssistantResponseTaskCoordinator;
import io.jmix.aitoolsflowui.view.chat.AiChatFragment;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubView;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubFragment;
import io.jmix.aitoolsflowui.view.input.AiChatInputFragment;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.Fragments;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.upload.TemporaryStorage;
import io.jmix.flowui.component.upload.UploadSucceededEvent;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.ai.schedule.*;
import ru.stnovator.finassist.ai.view.*;
import ru.stnovator.finassist.service.ScheduleComparison;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportStore.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

@UiTest
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class ScheduleImportUiTest {
    @Autowired
    Fragments fragments;
    @Autowired
    ViewNavigators navigators;
    @Autowired
    SystemAuthenticator auth;
    @Autowired
    Messages messages;
    @Autowired
    AiConversationService conversations;
    @Autowired
    AiChatMessageService chatMessages;
    @Autowired
    TemporaryStorage temporaryStorage;
    @Autowired
    io.jmix.aitoolsflowui.view.chat.support.TimelineItemFactory timelineFactory;
    @MockitoBean
    ScheduleAttachmentService attachments;
    @MockitoBean
    AssistantResponseTaskCoordinator coordinator;
    @MockitoBean
    ScheduleImportStore store;
    @MockitoBean
    ScheduleImportWorkflow workflow;
    @MockitoBean
    ScheduleImportTool tool;
    @MockitoBean
    ScheduleImportCommitService commit;
    @MockitoBean
    ScheduleConversationConfirmationService confirmations;
    @MockitoBean
    EntityLinksTool links;
    final UUID conversation = UUID.randomUUID(), operation = UUID.randomUUID();
    ScheduleImportPanel panel;
    ScheduleImportPreview preview;

    @BeforeEach
    void setup() {
        var rows = java.util.stream.IntStream.range(0, 150).mapToObj(i -> new Row(LocalDate.of(2027, 1, 1).plusDays(i), new BigDecimal("12.34"))).toList();
        var comparison = ScheduleComparison.compare(List.of(new ScheduleComparison.Entry(LocalDate.of(2026, 1, 1), BigDecimal.TEN)),
                rows.stream().map(r -> new ScheduleComparison.Entry(r.date(), r.amount())).toList());
        preview = new ScheduleImportPreview(operation, 7, "Contract", "Only project", Kind.PAYMENT, Mode.ADDENDUM, "A-1", LocalDate.of(2027, 1, 1), rows, comparison, comparison.afterTotal(), "secret", false);
        when(store.load(operation)).thenReturn(draft(conversation, 7, Status.PREVIEW_READY));
        when(tool.conversationState(conversation)).thenReturn(List.of(state("UI_CONFIRMATION")));
        when(workflow.getPreview(operation, 7)).thenReturn(preview);
        panel = new ScheduleImportPanel(store, workflow, tool, commit, links, messages, attachments, prompt -> {
        });
        panel.bind(conversation);
    }

    Draft draft(UUID chat, long revision, Status status) {
        return new Draft(operation, chat, revision, status, null, null, null, null,
                new DraftContent(Kind.PAYMENT, null, null, null, preview.rows(), UUID.randomUUID(), UUID.randomUUID(), Mode.ADDENDUM, "A-1", preview.effectiveDate(), "secret"));
    }

    ScheduleImportTool.State state(String next) {
        return new ScheduleImportTool.State(operation, 7, Status.PREVIEW_READY, Kind.PAYMENT, null, null, null, 150, preview.total(), null, null, Set.of(Mode.ADDENDUM), Mode.ADDENDUM, "A-1", preview.effectiveDate(), next, null);
    }

    @Test
    void uploadsOnlyAllowedFileTypes() {
        auth.runWithUser("admin", () -> {
            var input = (ScheduleAiChatInputFragment) fragments.create(UiTestUtils.getCurrentView(), AiChatInputFragment.class);
            var upload = input.getAttachmentUpload();
            assertThat(upload.getAcceptedFileTypes()).containsExactly(".xls", ".xlsx", ".csv");
            assertThat(upload.getMaxFiles()).isEqualTo(1);
            assertThat(upload.getMaxFileSize()).isEqualTo(25 * 1024 * 1024);
        });
    }

    void upload(ScheduleAiChatInputFragment input) {
        UUID file = temporaryStorage.saveFile("date;amount\n2027-01-01;12.34".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(attachments.stage(any(), eq("schedule.csv"), any())).thenReturn(UUID.randomUUID());
        startUpload(input);
        // The pinned temporary-storage handler emits transfer Finished before Success.
        finishTransfer(input);
        input.onAttachmentUploadSucceeded(new UploadSucceededEvent<>(input.getAttachmentUpload(),
                new TemporaryStorage.FileInfo(Objects.requireNonNull(temporaryStorage.getFile(file)), file), "schedule.csv", "text/csv", 30));
    }

    void startUpload(ScheduleAiChatInputFragment input) {
        ComponentUtil.fireEvent(input.getAttachmentUpload(), new StartedEvent(input.getAttachmentUpload(), "schedule.csv", "text/csv", 30));
    }

    void finishTransfer(ScheduleAiChatInputFragment input) {
        ComponentUtil.fireEvent(input.getAttachmentUpload(), new FinishedEvent(input.getAttachmentUpload(), "schedule.csv", "text/csv", 30));
    }

    void failUpload(ScheduleAiChatInputFragment input) {
        ComponentUtil.fireEvent(input.getAttachmentUpload(), new FailedEvent(input.getAttachmentUpload(), "schedule.csv", "text/csv", 30, new java.io.IOException("test failure")));
    }

    @Test
    void uploadCompletionAfterConversationSwitchIsDiscardedAndPreservesDisabledState() {
        auth.runWithUser("admin", () -> {
            var a = conversations.create(); var b = conversations.create();
            UUID file = temporaryStorage.saveFile("date;amount".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var temporaryFile = Objects.requireNonNull(temporaryStorage.getFile(file));
            try {
                var input = (ScheduleAiChatInputFragment) fragments.create(UiTestUtils.getCurrentView(), AiChatInputFragment.class);
                input.bindConversation(a); startUpload(input);
                input.bindConversation(b); input.setInputEnabled(false);
                input.onAttachmentUploadSucceeded(new UploadSucceededEvent<>(input.getAttachmentUpload(),
                        new TemporaryStorage.FileInfo(temporaryFile, file), "schedule.csv", "text/csv", 11));
                finishTransfer(input);
                verify(attachments, never()).stage(any(), any(), any());
                assertThat(input.getAttachmentConversation().getId()).isEqualTo(b.getId());
                assertThat(input.getPendingAttachment()).isNull();
                assertThat(temporaryFile).doesNotExist();
                assertThat(input.isInputEnabled()).isFalse();
            } finally { temporaryStorage.deleteFile(file); conversations.remove(a); conversations.remove(b); }
        });
    }

    @Test
    void lateUploadFailureDoesNotEnableSwitchedBusyComposer() {
        auth.runWithUser("admin", () -> {
            var a = conversations.create(); var b = conversations.create();
            try {
                var input = (ScheduleAiChatInputFragment) fragments.create(UiTestUtils.getCurrentView(), AiChatInputFragment.class);
                input.bindConversation(a); startUpload(input);
                input.bindConversation(b); input.setInputEnabled(false);
                failUpload(input); finishTransfer(input);
                assertThat(input.isInputEnabled()).isFalse();
                assertThat(input.getPendingAttachment()).isNull();
                verify(attachments, never()).stage(any(), any(), any());
            } finally { conversations.remove(a); conversations.remove(b); }
        });
    }

    @Test
    void transferFinishedBeforeSuccessKeepsComposerBlocked() {
        auth.runWithUser("admin", () -> {
            var chat = conversations.create();
            UUID file = temporaryStorage.saveFile("date;amount".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                var input = (ScheduleAiChatInputFragment) fragments.create(UiTestUtils.getCurrentView(), AiChatInputFragment.class);
                input.bindConversation(chat); startUpload(input); finishTransfer(input);
                assertThat(input.isUploading()).isTrue();
                assertThat(input.isInputEnabled()).isFalse();
                when(attachments.stage(eq(chat.getId()), any(), any())).thenReturn(UUID.randomUUID());
                input.onAttachmentUploadSucceeded(new UploadSucceededEvent<>(input.getAttachmentUpload(),
                        new TemporaryStorage.FileInfo(Objects.requireNonNull(temporaryStorage.getFile(file)), file), "schedule.csv", "text/csv", 11));
                assertThat(input.isUploading()).isFalse();
                assertThat(input.isInputEnabled()).isTrue();
            } finally { temporaryStorage.deleteFile(file); conversations.remove(chat); }
        });
    }

    @Test
    void textOnlyRetryPreservesLaterFileForNextUserMessage() { retryPreservesLaterFile(false); }

    @Test
    void attachedRetryPreservesLaterFileForNextUserMessage() { retryPreservesLaterFile(true); }

    void retryPreservesLaterFile(boolean originallyAttached) {
        auth.runWithUser("admin", () -> {
            var chat = conversations.create();
            try {
                navigators.view(UiTestUtils.getCurrentView(), AiChatView.class)
                        .withRouteParameters(new com.vaadin.flow.router.RouteParameters("id", chat.getId().toString())).navigate();
                var fragment = (ScheduleAiChatFragment) UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "chatFragment");
                var input = (ScheduleAiChatInputFragment) FragmentUtils.getComponent(fragment, "composerFragment");
                if (originallyAttached) upload(input);
                var originalAttachment = input.getPendingAttachment();
                doAnswer(invocation -> { ((Runnable) invocation.getArgument(5)).run(); return null; })
                        .when(coordinator).run(any(), any(), any(), any(), any(), any());
                fragment.sendMessage("Original submitted message");
                var original = chatMessages.loadMessages(chat).stream().findFirst().orElseThrow();
                upload(input); var later = input.getPendingAttachment();
                fragment.getRetryButton().click();
                verify(coordinator, times(2)).run(any(), any(), argThat(m -> m.getId().equals(original.getId())), any(), any(), any());
                verify(attachments, never()).bindToMessage(later.id(), original.getId());
                assertThat(input.getPendingAttachment()).isEqualTo(later);
                if (originallyAttached) verify(attachments, times(1)).bindToMessage(originalAttachment.id(), original.getId());
                assertThat(chatMessages.loadMessages(chat)).hasSize(1);
                fragment.sendMessage("Next submitted message");
                var next = chatMessages.loadMessages(chat).stream().filter(m -> !m.getId().equals(original.getId())).findFirst().orElseThrow();
                verify(attachments).bindToMessage(later.id(), next.getId());
            } finally { conversations.remove(chat); }
        });
    }

    @Test
    void retryWhileUploadingDoesNotProcessTheOldMessage() {
        auth.runWithUser("admin", () -> {
            var chat = conversations.create();
            try {
                navigators.view(UiTestUtils.getCurrentView(), AiChatView.class)
                        .withRouteParameters(new com.vaadin.flow.router.RouteParameters("id", chat.getId().toString())).navigate();
                var fragment = (ScheduleAiChatFragment) UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "chatFragment");
                var input = (ScheduleAiChatInputFragment) FragmentUtils.getComponent(fragment, "composerFragment");
                doAnswer(invocation -> { ((Runnable) invocation.getArgument(5)).run(); return null; })
                        .when(coordinator).run(any(), any(), any(), any(), any(), any());
                fragment.sendMessage("Original submitted message");
                startUpload(input); fragment.getRetryButton().click();
                verify(coordinator, times(1)).run(any(), any(), any(), any(), any(), any());
                assertThat(input.isUploading()).isTrue();
                failUpload(input);
            } finally { conversations.remove(chat); }
        });
    }

    @Test
    void canRemovePendingAttachment() {
        auth.runWithUser("admin", () -> {
            var input = (ScheduleAiChatInputFragment) fragments.create(UiTestUtils.getCurrentView(), AiChatInputFragment.class);
            upload(input);
            var pending = input.getPendingAttachment();
            var chat = input.getAttachmentConversation();
            try {
                input.removePendingAttachment();
                assertThat(input.getPendingAttachment()).isNull();
                verify(attachments).remove(pending.id());
            } finally {
                conversations.remove(chat);
            }
        });
    }

    @Test
    void firstHubUploadBindsExactPersistedUserBeforeProcessing() {
        auth.runWithUser("admin", () -> {
            navigators.view(UiTestUtils.getCurrentView(), AiChatHubView.class).navigate();
            var hub = (ScheduleAiChatHubFragment) UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "hubFragment");
            var input = (ScheduleAiChatInputFragment) FragmentUtils.getComponent(hub, "composerFragment");
            upload(input);
            var pending = input.getPendingAttachment();
            var chat = input.getAttachmentConversation();
            try {
                ((JmixTextArea) FragmentUtils.getComponent(input, "messageField")).setValue("Prepare payment schedule");
                ((JmixButton) FragmentUtils.getComponent(input, "sendButton")).click();
                assertThat((Object) UiTestUtils.getCurrentView()).isInstanceOf(AiChatView.class);
                var actual = chatMessages.loadMessages(chat).stream().findFirst().orElseThrow();
                var order = inOrder(attachments, coordinator);
                order.verify(attachments).bindToMessage(pending.id(), actual.getId());
                order.verify(coordinator).run(any(), any(), argThat(m -> m.getId().equals(actual.getId())), any(), any(), any());
                assertThat(chatMessages.loadMessages(chat)).hasSize(1);
            } finally {
                conversations.remove(chat);
            }
        });
    }

    @Test
    void submittedFileRendersWithActualUserAfterSendCompletionAndReload() {
        auth.runWithUser("admin", () -> {
            var chat=conversations.create();
            try {
                navigators.view(UiTestUtils.getCurrentView(),AiChatView.class)
                        .withRouteParameters(new com.vaadin.flow.router.RouteParameters("id",chat.getId().toString())).navigate();
                var fragment=(ScheduleAiChatFragment)UiTestUtils.getComponent(UiTestUtils.getCurrentView(),"chatFragment");
                var input=(ScheduleAiChatInputFragment)FragmentUtils.getComponent(fragment,"composerFragment");
                upload(input);var pending=input.getPendingAttachment();
                doAnswer(invocation->{
                    UUID actual=invocation.getArgument(1);
                    when(attachments.forMessage(chat.getId(),actual)).thenReturn(List.of(new ScheduleAttachmentService.SubmittedAttachment(pending.id(),pending.fileName(),pending.sizeBytes())));
                    return null;
                }).when(attachments).bindToMessage(eq(pending.id()),any());
                fragment.sendMessage("Prepare payment schedule");
                var actual=chatMessages.loadMessages(chat).iterator().next();
                assertThat(actual.getContent()).isEqualTo("Prepare payment schedule");
                assertThat(input.getPendingAttachment()).isNull();
                assertSentBadge(fragment,actual,pending.fileName());
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(fragment,"handleAssistantResponseDone",(Object)null);
                assertSentBadge(fragment,chatMessages.loadMessages(chat).iterator().next(),pending.fileName());
                fragment.setConversation(chat);
                assertSentBadge(fragment,chatMessages.loadMessages(chat).iterator().next(),pending.fileName());
            } finally {conversations.remove(chat);}
        });
    }
    void assertSentBadge(ScheduleAiChatFragment fragment,io.jmix.aitoolsflowui.model.AiChatMessage message,String name) {
        var renderer=fragments.create(fragment,io.jmix.aitoolsflowui.view.chat.renderer.TimelineItemRenderer.class);
        renderer.setItem(timelineFactory.createUserItem(message));
        assertThat(renderer.getContent().getElement().getTextRecursively()).contains(name);
        assertThat(renderer.getContent().getChildren().filter(c->c.getElement().hasAttribute("data-source-message-id")))
                .singleElement().satisfies(c->assertThat(c.getElement().getAttribute("data-source-message-id")).isEqualTo(message.getId().toString()));
        renderer.setItem(timelineFactory.createAssistantItem(message));
        assertThat(renderer.getContent().getElement().getTextRecursively()).doesNotContain(name);
    }

    @Test
    void staleTabAndItsRetryCaptureOnlyTheDisplayedProposal() {
        auth.runWithUser("admin", () -> {
            var chat = conversations.create();
            try {
                var first = chatMessages.createMessage(chat, AiChatMessageType.ASSISTANT, "Первое предложение");
                var displayed = new ScheduleConversationConfirmationService.Proposal(first.getId(), operation, 7, "first");
                when(confirmations.pendingProposal(chat.getId())).thenReturn(Optional.of(displayed));
                navigators.view(UiTestUtils.getCurrentView(), AiChatView.class)
                        .withRouteParameters(new com.vaadin.flow.router.RouteParameters("id", chat.getId().toString())).navigate();
                var fragment = (ScheduleAiChatFragment) UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "chatFragment");
                var newer = chatMessages.createMessage(chat, AiChatMessageType.ASSISTANT, "Второе предложение");
                when(confirmations.pendingProposal(chat.getId())).thenReturn(Optional.of(
                        new ScheduleConversationConfirmationService.Proposal(newer.getId(), operation, 8, "second")));
                doAnswer(i -> { ((Runnable) i.getArgument(5)).run(); return null; })
                        .when(coordinator).run(any(), any(), any(), any(), any(), any());
                fragment.sendMessage("Да");
                var actual = chatMessages.loadMessages(chat).stream().filter(m -> m.getType() == AiChatMessageType.USER)
                        .findFirst().orElseThrow();
                fragment.getRetryButton().click();
                verify(confirmations, times(2)).captureObservation(chat.getId(), actual.getId(), first.getId(), 7L);
            } finally {
                conversations.remove(chat);
            }
        });
    }

    @Test
    void directChatFailureRetriesSamePersistedUserWithoutDuplicateSubmission() {
        auth.runWithUser("admin", () -> {
            var chat = conversations.create();
            try {
                navigators.view(UiTestUtils.getCurrentView(), AiChatView.class)
                        .withRouteParameters(new com.vaadin.flow.router.RouteParameters("id", chat.getId().toString())).navigate();
                var fragment = (ScheduleAiChatFragment) UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "chatFragment");
                var input = (ScheduleAiChatInputFragment) FragmentUtils.getComponent(fragment, "composerFragment");
                upload(input);
                doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(5)).run();
                    return null;
                }).when(coordinator).run(any(), any(), any(), any(), any(), any());
                fragment.sendMessage("Prepare shipment schedule");
                var actual = chatMessages.loadMessages(chat).stream().findFirst().orElseThrow();
                Button retry = fragment.getRetryButton();
                retry.click();
                assertThat(chatMessages.loadMessages(chat)).hasSize(1);
                verify(coordinator, times(2)).run(any(), any(), argThat(m -> m.getId().equals(actual.getId())), any(), any(), any());
                verify(attachments, times(1)).bindToMessage(any(), eq(actual.getId()));
            } finally {
                conversations.remove(chat);
            }
        });
    }

    @Test
    void reloadRestoresSavedResultAlongsideActiveDraft() {
        UUID id = UUID.randomUUID();
        var receipt = new Draft(operation, conversation, 8, Status.SAVED, null, null, null, id, draft(conversation, 7, Status.SAVED).content());
        when(store.savedForConversation(conversation)).thenReturn(List.of(receipt));
        when(links.getEntityLinks("Addendum", List.of(id.toString()))).thenReturn(List.of(new EntityLinksTool.Link(true, "/addendums/" + id, "A-1")));
        // The same operation cannot be both active and saved: restore another receipt, preserving the active one.
        UUID saved = UUID.randomUUID();
        receipt = new Draft(saved, conversation, 8, Status.SAVED, null, null, null, id, receipt.content());
        when(store.savedForConversation(conversation)).thenReturn(List.of(receipt));
        when(store.load(saved)).thenReturn(receipt);
        panel.bind(conversation);
        assertThat(panel.getPreview()).isEqualTo(preview);
        assertThat(panel.getChildren().flatMap(c -> c.getChildren()).filter(c -> c instanceof com.vaadin.flow.component.html.Anchor).count()).isEqualTo(1);
    }

    @Test
    void savedResultCaptionsUseBusinessNamesWithoutTechnicalLabelsOrIds() {
        record CaptionCase(Kind kind, Mode mode, String entity, String expected) {}
        for (var item : List.of(new CaptionCase(Kind.PAYMENT, Mode.FILL, "PaymentSchedule", "Сохранён график оплат"),
                new CaptionCase(Kind.SHIPMENT, Mode.FILL, "ShipmentSchedule", "Сохранён график отгрузок"),
                new CaptionCase(Kind.PAYMENT, Mode.ADDENDUM, "Addendum", "Сохранено дополнительное соглашение"))) {
            UUID saved = UUID.randomUUID(), resultId = UUID.randomUUID();
            var content = new DraftContent(item.kind(), null, null, null, List.of(), null, null, item.mode(), null, null, null);
            var receipt = new Draft(saved, conversation, 8, Status.SAVED, null, null, null, resultId, content);
            when(store.savedForConversation(conversation)).thenReturn(List.of(receipt));
            when(store.load(saved)).thenReturn(receipt);
            when(links.getEntityLinks(item.entity(), List.of(resultId.toString())))
                    .thenReturn(List.of(new EntityLinksTool.Link(true, "/result/" + resultId, item.entity() + " " + resultId)));
            panel.bind(conversation);
            var captions = panel.getChildren().flatMap(com.vaadin.flow.component.Component::getChildren)
                    .filter(com.vaadin.flow.component.html.Anchor.class::isInstance)
                    .map(com.vaadin.flow.component.html.Anchor.class::cast).map(com.vaadin.flow.component.html.Anchor::getText).toList();
            assertThat(captions).containsExactly(item.expected());
            assertThat(captions).allSatisfy(caption -> assertThat(caption).doesNotContain("PaymentSchedule", "ShipmentSchedule", "Addendum", resultId.toString()));
        }
    }

    @Test
    void nativeHubAndDirectChatUseReplacement() {
        auth.runWithUser("admin", () -> {
            navigators.view(UiTestUtils.getCurrentView(), AiChatHubView.class).navigate();
            var hub = (AiChatHubFragment) UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "hubFragment");
            assertThat(hub).isInstanceOf(ScheduleAiChatHubFragment.class);
            assertThat(FragmentUtils.getComponent(hub, "composerFragment")).isInstanceOf(ScheduleAiChatInputFragment.class);
            assertThat(fragments.create(UiTestUtils.getCurrentView(), AiChatFragment.class)).isInstanceOf(ScheduleAiChatFragment.class);
        });
    }

    @Test
    void singleProjectNeedsNoPicker() {
        assertThat(panel.getPreview()).isEqualTo(preview);
    }

    @Test
    void rendersEveryPreviewRowAndAllChanges() {
        assertThat(panel.getRowsGrid().getListDataView().getItems().toList()).containsExactlyElementsOf(preview.rows());
        assertThat(panel.getChangesGrid().getListDataView().getItems().toList()).containsExactlyElementsOf(preview.comparison().rows());
    }

    @Test
    void confirmationKeepsSnapshotSummaryWhenResultLinkIsUnavailable() {
        var ui = com.vaadin.flow.component.UI.getCurrent();
        var previousLocale = ui.getLocale();
        ui.setLocale(Locale.forLanguageTag("ru-RU"));
        try {
            when(commit.confirm(operation, 7)).thenReturn(new ScheduleImportCommitService.CommitResult(operation, "Addendum", UUID.randomUUID(), false));
            // Resolver's empty result represents a missing or inaccessible destination.
            panel.getConfirmButton().click();
            assertThat(panel.getResult()).isNotNull();
            var visible = panel.getChildren().flatMap(com.vaadin.flow.component.Component::getChildren)
                    .filter(com.vaadin.flow.component.html.Span.class::isInstance)
                    .map(com.vaadin.flow.component.html.Span.class::cast).map(com.vaadin.flow.component.html.Span::getText).toList();
            assertThat(visible).anySatisfy(text -> assertThat(text.replace('\u00a0', ' ').replace('\u202f', ' '))
                    .contains("Сохранено дополнительное соглашение", "Строк: 150", "Итого: 1 851,00"));
            assertThat(visible).contains("Ссылка на сохранённый результат недоступна");
        } finally {
            ui.setLocale(previousLocale);
        }
    }

    @Test
    void confirmInvokesOnlyDisplayedRevision() {
        when(commit.confirm(operation, 7)).thenReturn(new ScheduleImportCommitService.CommitResult(operation, "PaymentSchedule", UUID.randomUUID(), false));
        panel.getConfirmButton().click();
        verify(commit).confirm(operation, 7);
    }

    @Test
    void switchingConversationClearsPendingConfirmation() {
        panel.bind(UUID.randomUUID());
        assertThat(panel.getPreview()).isNull();
        assertThat(panel.getConfirmButton().isEnabled()).isFalse();
        panel.getConfirmButton().click();
        verifyNoInteractions(commit);
    }

    @Test
    void sameUserCrossConversationCannotDisplayOrConfirm() {
        when(store.load(operation)).thenReturn(draft(UUID.randomUUID(), 7, Status.PREVIEW_READY));
        panel.showPreview(operation, 7);
        assertThat(panel.getConfirmButton().isEnabled()).isFalse();
        panel.getConfirmButton().click();
        verifyNoInteractions(commit);
    }

    @Test
    void sameUserCrossConversationCannotConfirmAfterDisplay() {
        when(store.load(operation)).thenReturn(draft(UUID.randomUUID(), 7, Status.PREVIEW_READY));
        panel.getConfirmButton().click();
        verifyNoInteractions(commit);
        assertThat(panel.getConfirmButton().isEnabled()).isFalse();
    }

    @Test
    void unavailableSelectionIsNeverConfirmable() {
        when(tool.conversationState(conversation)).thenReturn(List.of(state("SELECTION_UNAVAILABLE")));
        panel.bind(conversation);
        assertThat(panel.getConfirmButton().isEnabled()).isFalse();
        panel.showPreview(operation, 7);
        assertThat(panel.getConfirmButton().isEnabled()).isFalse();
    }

    @Test
    void changedRevisionOrHistoryRequiresNewPreview() {
        when(workflow.getPreview(operation, 7)).thenThrow(new ConcurrentModificationException());
        panel.getConfirmButton().click();
        verifyNoInteractions(commit);
        assertThat(panel.getConfirmButton().isEnabled()).isFalse();
    }

    @Test
    void doubleClickReturnsSameResult() {
        var result = new ScheduleImportCommitService.CommitResult(operation, "PaymentSchedule", UUID.randomUUID(), false);
        when(commit.confirm(operation, 7)).thenReturn(result);
        panel.getConfirmButton().click();
        panel.getConfirmButton().click();
        verify(commit, times(1)).confirm(operation, 7);
        assertThat(panel.getResult()).isEqualTo(result);
    }
}
