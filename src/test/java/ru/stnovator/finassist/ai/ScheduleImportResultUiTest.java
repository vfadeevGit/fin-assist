package ru.stnovator.finassist.ai;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
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
import ru.stnovator.finassist.ai.view.ScheduleImportPanel;
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
    @Autowired ScheduleImportTool tool;
    @Autowired ScheduleImportCommitService commit;
    @Autowired EntityLinksTool links;
    @Autowired Messages messages;
    @Autowired ScheduleAttachmentService attachments;
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

    ScheduleImportPanel prepared(Kind kind) {
        var draft = workflow.start(conversation, kind);
        draft = workflow.setRows(draft.operationId(), draft.revision(), List.of(
                new InputRow("2026-12-01", "1200.10", "1"),
                new InputRow("2026-12-02", "34.46", "2")), null);
        workflow.selectContract(draft.operationId(), draft.revision(), fixture.contract.getId());
        draft = store.load(draft.operationId());
        workflow.preview(draft.operationId(), draft.revision(), Mode.FILL, null, null);
        var panel = panel();
        panel.bind(conversation);
        assertThat(panel.getConfirmButton().isEnabled()).isTrue();
        return panel;
    }

    ScheduleImportPanel panel() {
        return new ScheduleImportPanel(store, workflow, tool, commit, links, messages, attachments, prompt -> {});
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void financeManagerCanFollowSavedBaseScheduleBeforeAndAfterReload(Kind kind) {
        var panel = prepared(kind);
        panel.getConfirmButton().click();
        assertThat(panel.getResult()).isNotNull();
        UUID resultId = panel.getResult().entityId();
        assertThat(anchors(panel)).singleElement().satisfies(link -> {
            assertThat(link.getHref()).endsWith("/" + resultId);
            UI.getCurrent().navigate(link.getHref().substring(1));
            if (kind == Kind.PAYMENT) {
                assertThat(((PaymentScheduleDetailView) UiTestUtils.getCurrentView()).getEditedEntity().getId()).isEqualTo(resultId);
            } else {
                assertThat(((ShipmentScheduleDetailView) UiTestUtils.getCurrentView()).getEditedEntity().getId()).isEqualTo(resultId);
            }
        });
        assertSummary(panel, kind);
        changeCurrentAmount(kind);
        var reloaded = panel();
        reloaded.bind(conversation);
        assertThat(anchors(reloaded)).singleElement().satisfies(link -> assertThat(link.getHref()).endsWith("/" + resultId));
        assertSummary(reloaded, kind);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void ownedHistoricalReceiptRemainsVisibleAfterDestinationReadIsRevoked(Kind kind) {
        var panel = prepared(kind);
        panel.getConfirmButton().click();
        assertThat(panel.getResult()).isNotNull();
        changeCurrentAmount(kind);
        DashboardFixtures.authenticate(owner, "ROLE_ai-schedule-import");
        var reloaded = panel();
        reloaded.bind(conversation);
        assertThat(anchors(reloaded)).isEmpty();
        assertThat(reloaded.isVisible()).isTrue();
        assertSummary(reloaded, kind);
        assertThat(spans(reloaded)).contains("Ссылка на сохранённый результат недоступна");
        assertThat(spans(reloaded)).allSatisfy(text -> assertThat(text).doesNotContain("999", "related-", panel.getResult().entityId().toString()));
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

    void assertSummary(ScheduleImportPanel panel, Kind kind) {
        String caption = kind == Kind.PAYMENT ? "Сохранён график оплат" : "Сохранён график отгрузок";
        assertThat(spans(panel)).anySatisfy(text -> assertThat(text.replace('\u00a0', ' ').replace('\u202f', ' '))
                .contains(caption, "Строк: 2", "Итого: 1 234,56"));
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
