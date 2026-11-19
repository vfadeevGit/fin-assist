package ru.stnovator.finassist.ai;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.markdown.Markdown;
import com.vaadin.flow.router.RouteParameters;
import io.jmix.aitoolsflowuidata.entity.AiConversationEntity;
import io.jmix.core.*;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.stnovator.finassist.FinAssistApplication;
import ru.stnovator.finassist.ai.schedule.*;
import io.jmix.aitoolsflowui.model.AiChatMessageType;
import io.jmix.aitoolsflowui.service.AiChatMessageService;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.aitoolsflowui.view.chat.support.TimelineItemFactory;
import io.jmix.aitoolsflowui.view.chat.AiChatView;
import io.jmix.aitoolsflowui.view.chat.timeline.TimelineItem;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubView;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.model.CollectionContainer;
import org.springframework.test.util.ReflectionTestUtils;
import ru.stnovator.finassist.ai.view.ScheduleAiChatFragment;
import io.jmix.aitoolsflowui.view.chat.renderer.TimelineItemRenderer;
import io.jmix.flowui.Fragments;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.test_support.DashboardFixtures;
import ru.stnovator.finassist.test_support.RelatedFixtures;
import ru.stnovator.finassist.view.paymentschedule.PaymentScheduleDetailView;
import ru.stnovator.finassist.view.shipmentschedule.ShipmentScheduleDetailView;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

/** Real persistence, finance-manager policies and permission-aware result links; no model calls. */
@UiTest
@SpringBootTest(classes = {FinAssistApplication.class, FlowuiTestAssistConfiguration.class})
class ScheduleImportResultUiTest {
    @Autowired DataManager data;
    @Autowired UnconstrainedDataManager internal;
    @Autowired SystemAuthenticator auth;
    @Autowired ScheduleImportStore store;
    @Autowired ScheduleImportWorkflow workflow;
    @Autowired ScheduleImportCommitService commit;
    @Autowired ViewNavigators navigators;
    @Autowired ScheduleAiChatDataService chat;
    @Autowired ScheduleConversationConfirmationService confirmations;
    @Autowired AiConversationService conversations;
    @Autowired AiChatMessageService messages;
    @Autowired ScheduleProposalText presentation;
    @Autowired Fragments fragments;
    @Autowired TimelineItemFactory timeline;
    RelatedFixtures fixture;
    User owner;
    UUID conversation;
    Authentication original;
    Locale originalLocale;

    @BeforeEach
    void setup() {
        original = SecurityContextHolder.getContext().getAuthentication();
        originalLocale = UI.getCurrent().getLocale();
        UI.getCurrent().setLocale(Locale.forLanguageTag("ru-RU"));
        auth.runWithUser("admin", () -> {
            fixture = new RelatedFixtures(data, auth);
            owner = data.create(User.class);
            owner.setUsername("result-" + UUID.randomUUID());
            owner.setPassword("{noop}test");
            owner = fixture.save(owner);
            var chat = internal.create(AiConversationEntity.class);
            chat.setUsername(owner.getUsername());
            chat.setTitle("result receipt test");
            conversation = internal.save(chat).getId();
        });
        DashboardFixtures.authenticate(owner, "ROLE_finance-manager-role");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.getContext().setAuthentication(original);
        UI.getCurrent().setLocale(originalLocale);
        auth.runWithUser("admin", () -> {
            internal.load(AiScheduleImport.class).query("e.owner = :owner")
                    .parameter("owner", owner.getUsername()).list().forEach(internal::remove);
            internal.load(AiConversationEntity.class).id(conversation).optional().ifPresent(internal::remove);
            for (Class<?> type : List.of(PaymentScheduleItem.class, ShipmentScheduleItem.class)) {
                internal.load(type).query("select e from " + type.getSimpleName() + " e where e.schedule.project.id = :project")
                        .parameter("project", fixture.project.getId()).hint(PersistenceHints.SOFT_DELETION, false).list()
                        .forEach(e -> internal.save(new SaveContext().removing(e).setHint(PersistenceHints.SOFT_DELETION, false)));
            }
        });
        fixture.close();
    }

    private ScheduleImportPreview prepare(Kind kind) {
        var draft = workflow.start(conversation, kind);
        draft = workflow.setRows(draft.operationId(), draft.revision(), List.of(
                new InputRow("2026-12-01", "1200.10", "1"),
                new InputRow("2026-12-02", "34.46", "2")), null);
        workflow.selectContract(draft.operationId(), draft.revision(), fixture.contract.getId());
        draft = store.load(draft.operationId());
        return workflow.preview(draft.operationId(), draft.revision(), Mode.FILL, null, null);
    }

    UUID savedReceipt(Kind kind) {
        var preview = prepare(kind);
        var nativeChat = Objects.requireNonNull(conversations.loadConversation(conversation));
        var proposal = messages.createMessage(nativeChat, AiChatMessageType.ASSISTANT, presentation.render(preview, "Текст"));
        confirmations.publishProposal(preview.operationId(), preview.revision(), proposal.getId());
        var actual = messages.createMessage(nativeChat, AiChatMessageType.USER, "Да");
        confirmations.captureObservation(conversation, actual.getId(), proposal.getId(), preview.revision());
        assertThat(chat.processMessage(actual)).contains("Сохранено");
        return preview.operationId();
    }

    Component renderReceipt(UUID messageId) {
        var nativeChat = Objects.requireNonNull(conversations.loadConversation(conversation));
        var message = messages.loadMessages(nativeChat).stream().filter(m -> m.getId().equals(messageId)).findFirst().orElseThrow();
        assertThat(message.getContent()).contains("Строк: 2", "1234.56").doesNotContain("](");
        var renderer = fragments.create(UiTestUtils.getCurrentView(), TimelineItemRenderer.class);
        renderer.setItem(timeline.createAssistantItem(message));
        return renderer.getContent();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void financeManagerCanFollowSavedBaseScheduleBeforeAndAfterReload(Kind kind) {
        UUID receipt = savedReceipt(kind);
        UUID resultId = store.load(receipt).resultId();
        var panel = renderReceipt(receipt);
        assertThat(anchors(panel)).singleElement().satisfies(link -> {
            assertThat(link.getHref()).endsWith("/" + resultId);
            UI.getCurrent().navigate(link.getHref().substring(1));
            if (kind == Kind.PAYMENT) {
                assertThat(((PaymentScheduleDetailView) UiTestUtils.getCurrentView()).getEditedEntity().getId()).isEqualTo(resultId);
            } else {
                assertThat(((ShipmentScheduleDetailView) UiTestUtils.getCurrentView()).getEditedEntity().getId()).isEqualTo(resultId);
            }
        });
        changeCurrentAmount(kind);
        var reloaded = renderReceipt(receipt);
        assertThat(anchors(reloaded)).singleElement().satisfies(link -> assertThat(link.getHref()).endsWith("/" + resultId));
        assertThat(timelineItems(openTimeline(conversation)).stream().filter(item -> receipt.equals(item.getMessage().getId())))
                .hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void ownedHistoricalReceiptRemainsVisibleAfterDestinationReadIsRevoked(Kind kind) {
        UUID receipt = savedReceipt(kind);
        UUID resultId = store.load(receipt).resultId();
        changeCurrentAmount(kind);
        DashboardFixtures.authenticate(owner, "ROLE_ai-schedule-import");
        var reloaded = renderReceipt(receipt);
        assertThat(anchors(reloaded)).isEmpty();
        assertThat(reloaded.isVisible()).isTrue();
        assertThat(spans(reloaded)).contains("Ссылка на сохранённый результат недоступна");
        assertThat(spans(reloaded)).allSatisfy(text -> assertThat(text).doesNotContain("999", "related-", resultId.toString()));
    }

    @Test
    void legacySaveWithoutAnAssistantMessageRestoresOneReceiptAndChecksTheCurrentLinkAccess() {
        var preview = prepare(Kind.PAYMENT);
        var saved = commit.confirm(preview.operationId(), preview.revision());
        var nativeChat = Objects.requireNonNull(conversations.loadConversation(conversation));
        assertThat(messages.loadMessages(nativeChat)).isEmpty();
        changeCurrentAmount(Kind.PAYMENT);

        for (int visit = 0; visit < 2; visit++) {
            var receipt = renderBoundLegacyReceipt(saved.operationId());
            assertThat(anchors(receipt)).singleElement()
                    .satisfies(link -> assertThat(link.getHref()).endsWith("/" + saved.entityId()));
        }

        var other = conversations.create();
        try {
            assertThat(timelineItems(openTimeline(other.getId()))).isEmpty();
        } finally {
            conversations.remove(other);
        }

        DashboardFixtures.authenticate(owner, "ROLE_ai-schedule-import");
        var receipt = renderBoundLegacyReceipt(saved.operationId());
        assertThat(anchors(receipt)).isEmpty();
        assertThat(spans(receipt)).contains("Ссылка на сохранённый результат недоступна");
        assertThat(messages.loadMessages(nativeChat)).isEmpty();
    }

    private Component renderBoundLegacyReceipt(UUID operationId) {
        var fragment = openTimeline(conversation);
        var items = timelineItems(fragment);
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.getMessage().getId()).isEqualTo(operationId);
            assertThat(item.getMessage().getContent()).contains("Строк: 2", "1234.56").doesNotContain("999", "](");
        });
        var renderer = fragments.create(fragment, TimelineItemRenderer.class);
        renderer.setItem(items.getFirst());
        assertThat(components(renderer.getContent()).filter(Markdown.class::isInstance)
                .map(Markdown.class::cast).map(Markdown::getContent))
                .singleElement().satisfies(text -> assertThat(text).contains("Строк: 2", "1234.56"));
        return renderer.getContent();
    }

    private ScheduleAiChatFragment openTimeline(UUID chatId) {
        navigators.view(UiTestUtils.getCurrentView(), AiChatHubView.class).navigate();
        navigators.view(UiTestUtils.getCurrentView(), AiChatView.class)
                .withRouteParameters(new RouteParameters("id", chatId.toString())).navigate();
        return (ScheduleAiChatFragment) UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "chatFragment");
    }

    @SuppressWarnings("unchecked")
    private List<TimelineItem> timelineItems(ScheduleAiChatFragment fragment) {
        var container = (CollectionContainer<TimelineItem>) Objects.requireNonNull(
                ReflectionTestUtils.getField(fragment, "timelineItemsDc"));
        return container.getItems();
    }

    void changeCurrentAmount(Kind kind) {
        auth.runWithUser("admin", () -> {
            if (kind == Kind.PAYMENT) {
                var item = data.load(PaymentScheduleItem.class).query("e.schedule.id = :id").parameter("id", fixture.payment.getId()).list().getFirst();
                item.setAmount(new BigDecimal("999.00"));
                data.saveWithoutReload(item);
            } else {
                var item = data.load(ShipmentScheduleItem.class).query("e.schedule.id = :id").parameter("id", fixture.shipment.getId()).list().getFirst();
                item.setAmount(new BigDecimal("999.00"));
                data.saveWithoutReload(item);
            }
        });
    }

    static Stream<Component> components(Component root) {
        return Stream.concat(Stream.of(root), root.getChildren().flatMap(ScheduleImportResultUiTest::components));
    }

    static List<Anchor> anchors(Component root) {
        return components(root).filter(Anchor.class::isInstance).map(Anchor.class::cast).toList();
    }

    static List<String> spans(Component root) {
        return components(root).filter(Span.class::isInstance).map(Span.class::cast).map(Span::getText).toList();
    }
}
