package ru.stnovator.finassist.ai;

import com.vaadin.flow.component.button.Button;
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
        return new ScheduleImportTool.State(operation, 7, Status.PREVIEW_READY, Kind.PAYMENT, null, null, null, 150, preview.total(), null, null, Set.of(Mode.ADDENDUM), Mode.ADDENDUM, "A-1", preview.effectiveDate(), next);
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
        input.onAttachmentUploadSucceeded(new UploadSucceededEvent<>(input.getAttachmentUpload(),
                new TemporaryStorage.FileInfo(Objects.requireNonNull(temporaryStorage.getFile(file)), file), "schedule.csv", "text/csv", 30));
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
                assertThat(fragment.getImportPanel()).isNotNull();
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
