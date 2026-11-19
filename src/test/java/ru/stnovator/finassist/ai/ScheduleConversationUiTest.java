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
import io.jmix.aitoolsflowui.view.chat.timeline.TimelineItem;
import io.jmix.aitoolsflowui.view.chat.AiChatFragment;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubView;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubFragment;
import io.jmix.aitoolsflowui.view.input.AiChatInputFragment;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.Fragments;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.model.CollectionContainer;
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
import ru.stnovator.finassist.ai.schedule.ScheduleImportStore.AttachmentProcessing;
import ru.stnovator.finassist.ai.schedule.ScheduleImportStore.AttachmentStage;
import ru.stnovator.finassist.ai.view.*;
import ru.stnovator.finassist.service.ScheduleComparison;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

@UiTest
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class ScheduleConversationUiTest {
    @Autowired
    io.jmix.core.UnconstrainedDataManager nativeData;
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
    ScheduleConversationConfirmationService confirmations;
    final UUID operation = UUID.randomUUID();

    @BeforeEach
    void setup() {
        when(store.attachmentProcessing(any(UUID.class), any(UUID.class), any(UUID.class)))
                .thenReturn(new AttachmentProcessing(AttachmentStage.ATTACHED, null, null, null, null, 0, null, null));
        when(attachments.listSubmitted(any(UUID.class), anyInt()))
                .thenReturn(new ScheduleAttachmentService.SubmittedPage(List.of(), 0, null));
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
                var card = (com.vaadin.flow.component.orderedlayout.VerticalLayout) FragmentUtils.getComponent(input, "pendingAttachment");
                assertThat(card.getElement().getTextRecursively()).contains(pending.fileName(), "28");
                var remove = card.getChildren().flatMap(com.vaadin.flow.component.Component::getChildren)
                        .filter(Button.class::isInstance).map(Button.class::cast).findFirst().orElseThrow();
                remove.click();
                assertThat(card.isVisible()).isFalse();
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
    void conversationFilesStartsCollapsedAndPagesOnlyBoundConversation() {
        auth.runWithUser("admin", () -> {
            var chat = conversations.create();
            var other = conversations.create();
            var first = new ScheduleAttachmentService.SubmittedAttachment(UUID.randomUUID(), "recent.csv", 30);
            var older = new ScheduleAttachmentService.SubmittedAttachment(UUID.randomUUID(), "older.csv", 40);
            var empty = new ScheduleAttachmentService.SubmittedPage(List.of(), 0, null);
            try {
                when(attachments.listSubmitted(chat.getId(), 0))
                        .thenReturn(new ScheduleAttachmentService.SubmittedPage(List.of(first), 2, 1));
                when(attachments.listSubmitted(chat.getId(), 1))
                        .thenReturn(new ScheduleAttachmentService.SubmittedPage(List.of(older), 2, null));
                when(attachments.listSubmitted(other.getId(), 0)).thenReturn(empty);
                navigators.view(UiTestUtils.getCurrentView(), AiChatView.class)
                        .withRouteParameters(new com.vaadin.flow.router.RouteParameters("id", chat.getId().toString())).navigate();
                var fragment = (ScheduleAiChatFragment) UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "chatFragment");
                var overview = conversationFileOverview(fragment);
                assertThat(overview).isNotNull();
                assertThat(overview.isOpened()).isFalse();
                assertThat(overview.getSummaryText()).endsWith(" · 2");
                overview.setOpened(true);
                var more = overview.getContent().flatMap(com.vaadin.flow.component.Component::getChildren)
                        .filter(Button.class::isInstance).map(Button.class::cast).findFirst().orElseThrow();
                more.click();
                assertThat(overview.getElement().getTextRecursively()).contains("recent.csv", "older.csv");
                fragment.setConversation(other);
                assertThat(overview.isOpened()).isFalse();
                assertThat(overview.getSummaryText()).endsWith(" · 0");
                assertThat(overview.getElement().getTextRecursively()).doesNotContain("recent.csv", "older.csv");
            } finally {
                conversations.remove(chat);
                conversations.remove(other);
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
                    when(attachments.forMessage(chat.getId(),actual)).thenReturn(List.of(new ScheduleAttachmentService.SubmittedAttachment(pending.id(),pending.fileName(),pending.sizeBytes(),true,actual,null)));
                    return null;
                }).when(attachments).bindToMessage(eq(pending.id()),any());
                fragment.sendMessage("Prepare payment schedule");
                var actual=chatMessages.loadMessages(chat).iterator().next();
                assertThat(actual.getContent()).isEqualTo("Prepare payment schedule");
                assertThat(input.getPendingAttachment()).isNull();
                assertSentBadge(fragment,actual,pending.fileName());
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(fragment,"handleAssistantResponseDone",(Object)null);
                assertSentBadge(fragment,chatMessages.loadMessages(chat).iterator().next(),pending.fileName());
                var file = new ScheduleAttachmentService.SubmittedAttachment(pending.id(), pending.fileName(),
                        pending.sizeBytes(), true, actual.getId(), null);
                when(attachments.listSubmitted(chat.getId(), 0))
                        .thenReturn(new ScheduleAttachmentService.SubmittedPage(List.of(file), 1, null));
                var requestAt = java.time.OffsetDateTime.parse("2026-11-19T09:00:00+03:00");
                when(store.attachmentProcessing(chat.getId(), pending.id(), actual.getId()))
                        .thenReturn(new AttachmentProcessing(AttachmentStage.PARSED, operation, actual.getId(), requestAt,
                                Kind.PAYMENT, 2, new BigDecimal("120.00"), null));
                fragment.setConversation(chat);
                var overview = conversationFileOverview(fragment);
                overview.setOpened(true);
                assertThat(overview.getElement().getTextRecursively()).contains("График распознан", "120", "График оплат");
                var processed = fragments.create(fragment, io.jmix.aitoolsflowui.view.chat.renderer.TimelineItemRenderer.class);
                processed.setItem(timelineFactory.createUserItem(actual));
                assertThat(processed.getContent().getElement().getTextRecursively()).contains("График распознан", "120");
                when(store.attachmentProcessing(chat.getId(), pending.id(), actual.getId()))
                        .thenReturn(new AttachmentProcessing(AttachmentStage.ERROR, operation, actual.getId(), requestAt,
                                Kind.PAYMENT, 0, null, "INVALID_AMOUNT"));
                fragment.setConversation(chat);
                assertThat(overview.getElement().getTextRecursively()).contains("некорректная сумма")
                        .doesNotContain("График распознан");
                assertSentBadge(fragment,chatMessages.loadMessages(chat).iterator().next(),pending.fileName());
                when(attachments.forMessage(chat.getId(), actual.getId())).thenReturn(List.of(
                        new ScheduleAttachmentService.SubmittedAttachment(pending.id(), pending.fileName(),
                                pending.sizeBytes(), false, actual.getId(), null)));
                var renderer = fragments.create(fragment, io.jmix.aitoolsflowui.view.chat.renderer.TimelineItemRenderer.class);
                renderer.setItem(timelineFactory.createUserItem(actual));
                assertThat(renderer.getContent().getElement().getTextRecursively()).contains(pending.fileName(), "Недоступен");
                assertThat(renderer.getContent().getChildren().flatMap(com.vaadin.flow.component.Component::getChildren)
                        .filter(com.vaadin.flow.component.html.Anchor.class::isInstance)).isEmpty();
            } finally {conversations.remove(chat);}
        });
    }
    @Test
    void nativeFailureCallbackRefreshesHistoricalFileStatusAndPreservesRetry() {
        auth.runWithUser("admin", () -> {
            var chat = conversations.create();
            try {
                var source = chatMessages.createMessage(chat, AiChatMessageType.USER, "Original file");
                var file = new ScheduleAttachmentService.SubmittedAttachment(UUID.randomUUID(), "history.csv",
                        44, true, source.getId(), null);
                when(attachments.forMessage(chat.getId(), source.getId())).thenReturn(List.of(file));
                when(attachments.listSubmitted(chat.getId(), 0))
                        .thenReturn(new ScheduleAttachmentService.SubmittedPage(List.of(file), 1, null));
                navigators.view(UiTestUtils.getCurrentView(), AiChatView.class)
                        .withRouteParameters(new com.vaadin.flow.router.RouteParameters("id", chat.getId().toString())).navigate();
                var fragment = (ScheduleAiChatFragment) UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "chatFragment");
                var overview = conversationFileOverview(fragment);
                overview.setOpened(true);
                assertThat(overview.getElement().getTextRecursively()).contains("Прикреплён к сообщению");

                var items = timelineItems(fragment);
                var refreshed = new ArrayList<UUID>();
                items.addCollectionChangeListener(event -> {
                    if (event.getChangeType() == io.jmix.flowui.model.CollectionChangeType.SET_ITEM) {
                        event.getChanges().stream().map(item -> item.getMessage().getId()).forEach(refreshed::add);
                    }
                });
                doAnswer(invocation -> {
                    var request = (io.jmix.aitoolsflowui.model.AiChatMessage) invocation.getArgument(2);
                    when(store.attachmentProcessing(chat.getId(), file.id(), source.getId()))
                            .thenReturn(new AttachmentProcessing(AttachmentStage.ERROR, operation, request.getId(),
                                    java.time.OffsetDateTime.parse("2026-11-19T09:00:00+03:00"), Kind.PAYMENT,
                                    0, null, "INVALID_AMOUNT"));
                    ((Runnable) invocation.getArgument(5)).run();
                    return null;
                }).when(coordinator).run(any(), any(), any(), any(), any(), any());

                fragment.sendMessage("Reuse the original attachment");
                assertThat(overview.getElement().getTextRecursively()).contains("некорректная сумма");
                assertThat(refreshed).contains(source.getId());
                assertThat(fragment.getRetryButton().isVisible()).isTrue();
                assertThat(items.getItems()).anySatisfy(item ->
                        assertThat(item.getType()).isEqualTo(io.jmix.aitoolsflowui.view.chat.timeline.TimelineItemType.ASSISTANT));
            } finally {
                conversations.remove(chat);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private CollectionContainer<TimelineItem> timelineItems(ScheduleAiChatFragment fragment) {
        return (CollectionContainer<TimelineItem>) Objects.requireNonNull(
                org.springframework.test.util.ReflectionTestUtils.getField(fragment, "timelineItemsDc"));
    }

    private com.vaadin.flow.component.details.Details conversationFileOverview(ScheduleAiChatFragment fragment) {
        return fragment.getContent().getChildren().flatMap(com.vaadin.flow.component.Component::getChildren)
                .flatMap(com.vaadin.flow.component.Component::getChildren)
                .filter(component -> component.getId().orElse("").equals("scheduleConversationFiles"))
                .map(com.vaadin.flow.component.details.Details.class::cast).findFirst().orElseThrow();
    }

    void assertSentBadge(ScheduleAiChatFragment fragment,io.jmix.aitoolsflowui.model.AiChatMessage message,String name) {
        var renderer=fragments.create(fragment,io.jmix.aitoolsflowui.view.chat.renderer.TimelineItemRenderer.class);
        renderer.setItem(timelineFactory.createUserItem(message));
        assertThat(renderer.getContent().getElement().getTextRecursively()).contains(name);
        assertThat(renderer.getContent().getChildren().filter(c->c.getElement().hasAttribute("data-source-message-id")))
                .singleElement().satisfies(c->assertThat(c.getElement().getAttribute("data-source-message-id")).isEqualTo(message.getId().toString()));
        assertThat(renderer.getContent().getChildren().flatMap(com.vaadin.flow.component.Component::getChildren)
                .filter(com.vaadin.flow.component.html.Anchor.class::isInstance)
                .map(com.vaadin.flow.component.html.Anchor.class::cast))
                .singleElement().satisfies(link -> assertThat(link.getHref()).isNotBlank());
        renderer.setItem(timelineFactory.createAssistantItem(message));
        assertThat(renderer.getContent().getElement().getTextRecursively()).doesNotContain(name);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Первое предложение", "", "Другая версия"})
    void staleTabAndItsRetryCaptureOnlyTheDisplayedProposal(String boundContent) {
        auth.runWithUser("admin", () -> {
            var chat = conversations.create();
            try {
                var row = nativeData.create(io.jmix.aitoolsflowuidata.entity.AiChatMessageEntity.class);
                row.setConversation(nativeData.load(io.jmix.aitoolsflowuidata.entity.AiConversationEntity.class).id(chat.getId()).one());
                row.setType(io.jmix.aitoolsflowuidata.entity.AiChatMessageEntityType.ASSISTANT);
                row.setContent(boundContent);
                var first = nativeData.save(row);
                var displayed = new ScheduleConversationConfirmationService.Proposal(first.getId(), operation, 7, "first", "37304bd6cda95c858e57015f7c945a7bc23993a811b6dbc73bc76f686730603b");
                when(confirmations.pendingProposal(chat.getId())).thenReturn(Optional.of(displayed));
                navigators.view(UiTestUtils.getCurrentView(), AiChatView.class)
                        .withRouteParameters(new com.vaadin.flow.router.RouteParameters("id", chat.getId().toString())).navigate();
                var fragment = (ScheduleAiChatFragment) UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "chatFragment");
                var newer = chatMessages.createMessage(chat, AiChatMessageType.ASSISTANT, "Второе предложение");
                when(confirmations.pendingProposal(chat.getId())).thenReturn(Optional.of(
                        new ScheduleConversationConfirmationService.Proposal(newer.getId(), operation, 8, "second", "46b249d773d171e7a6ea688d4290460fc74d8f1321023fbc00204e7abe50705c")));
                doAnswer(i -> { ((Runnable) i.getArgument(5)).run(); return null; })
                        .when(coordinator).run(any(), any(), any(), any(), any(), any());
                fragment.sendMessage("Да");
                var actual = chatMessages.loadMessages(chat).stream().filter(m -> m.getType() == AiChatMessageType.USER)
                        .findFirst().orElseThrow();
                fragment.getRetryButton().click();
                boolean full = "Первое предложение".equals(boundContent);
                verify(confirmations, times(2)).captureObservation(chat.getId(), actual.getId(),
                        full ? first.getId() : null, full ? 7L : null);
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
    void nativeTimelineRestoresEveryProposalRowAndKeepsTheQuestionOutsideTheTables() {
        var dates = LocalDate.of(2027, 1, 1).datesUntil(LocalDate.of(2027, 5, 31)).toList();
        var rows = dates.stream().map(date -> new Row(date, new BigDecimal("12.34"))).toList();
        var comparison = ScheduleComparison.compare(List.of(new ScheduleComparison.Entry(LocalDate.of(2026, 1, 1), BigDecimal.TEN)),
                rows.stream().map(r -> new ScheduleComparison.Entry(r.date(), r.amount())).toList());
        var preview = new ScheduleImportPreview(operation, 7, "Contract", "Only project", Kind.PAYMENT, Mode.ADDENDUM,
                "A-1", LocalDate.of(2027, 1, 1), rows, comparison, comparison.afterTotal(), "fingerprint", false);
        auth.runWithUser("admin", () -> {
            var chat = conversations.create();
            try {
                var presentation = new ScheduleProposalText(messages);
                var proposal = chatMessages.createMessage(chat, AiChatMessageType.ASSISTANT,
                        presentation.afterHistoryChange(presentation.render(preview, "Январь <img src=x onerror=alert(1)>.xls")));
                for (int visit = 0; visit < 2; visit++) {
                    navigators.view(UiTestUtils.getCurrentView(), AiChatHubView.class).navigate();
                    navigators.view(UiTestUtils.getCurrentView(), AiChatView.class)
                            .withRouteParameters(new com.vaadin.flow.router.RouteParameters("id", chat.getId().toString())).navigate();
                    var fragment = (ScheduleAiChatFragment) UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "chatFragment");
                    assertThat(ScheduleImportResultUiTest.components(fragment.getContent()).flatMap(c -> c.getId().stream()))
                            .doesNotContain("scheduleImportPanel", "scheduleConfirm", "scheduleCorrect",
                                    "scheduleCancel", "scheduleRefresh");

                    assertThat(timelineItems(fragment).getItems()).singleElement().satisfies(item -> {
                        assertThat(item.getMessage().getId()).isEqualTo(proposal.getId());
                        var renderer = fragments.create(fragment, io.jmix.aitoolsflowui.view.chat.renderer.TimelineItemRenderer.class);
                        renderer.setItem(item);
                        var markdown = ScheduleImportResultUiTest.components(renderer.getContent())
                                .filter(com.vaadin.flow.component.markdown.Markdown.class::isInstance)
                                .map(com.vaadin.flow.component.markdown.Markdown.class::cast).findFirst().orElseThrow();
                        var content = markdown.getContent();
                        assertThat(content).startsWith("После предпросмотра данные изменились.")
                                .contains("Contract", "Only project", "Январь &#60;img src&#61;x", "1851.00")
                                .doesNotContain("<img", "onerror=")
                                .contains("class=\"schedule-proposal-table\" role=\"region\" tabindex=\"0\" aria-label=\"Полный график\"",
                                        "class=\"schedule-proposal-table\" role=\"region\" tabindex=\"0\" aria-label=\"Изменения\"")
                                .endsWith("</div>\n\nПрименить эти изменения?");
                        var scheduleRows = content.lines().filter(line -> line.endsWith("| 12.34 |")
                                && line.split("\\|").length == 3).toList();
                        assertThat(scheduleRows).hasSize(150)
                                .containsExactlyElementsOf(dates.stream().map(date -> "| " + date + " | 12.34 |").toList());
                        var addedRows = content.lines().filter(line -> line.endsWith("| — | 12.34 | 12.34 |")).toList();
                        assertThat(addedRows).hasSize(150)
                                .containsExactlyElementsOf(dates.stream().map(date -> "| " + date + " | — | 12.34 | 12.34 |").toList());
                        assertThat(content).contains("| 2026-01-01 | 10 | — | -10 |");
                    });
                }
            } finally {
                conversations.remove(chat);
            }
        });
    }

}
