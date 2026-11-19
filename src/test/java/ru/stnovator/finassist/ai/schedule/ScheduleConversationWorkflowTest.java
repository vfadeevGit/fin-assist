package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitoolsflowui.model.AiChatMessage;
import io.jmix.aitoolsflowuidata.entity.*;
import io.jmix.core.UnconstrainedDataManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import ru.stnovator.finassist.ai.ScheduleAiChatDataService;

import static io.jmix.aitoolsflowuidata.entity.AiChatMessageEntityType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

@SpringBootTest
@Import(ScheduleImportCommitServiceTest.FailureConfiguration.class)
class ScheduleConversationWorkflowTest {
    @Autowired ScheduleAiChatDataService chat;
    @Autowired ScheduleConversationConfirmationService confirmations;
    @Autowired UnconstrainedDataManager data;
    @Autowired ApplicationContext context;
    final ScheduleImportCommitServiceTest fixture = new ScheduleImportCommitServiceTest();
    Object previousClient;

    @BeforeEach void setup() {
        context.getAutowireCapableBeanFactory().autowireBean(fixture);
        fixture.setup();
        previousClient = ReflectionTestUtils.getField(chat, "chatClient");
        ReflectionTestUtils.setField(chat, "chatClient", null);
    }

    @AfterEach void cleanup() {
        ReflectionTestUtils.setField(chat, "chatClient", previousClient);
        fixture.cleanup();
    }

    AiChatMessageEntity message(AiChatMessageEntityType type, String text) {
        var value = data.create(AiChatMessageEntity.class);
        value.setConversation(data.load(AiConversationEntity.class).id(fixture.conversation).one());
        value.setType(type);
        value.setContent(text);
        return data.save(value);
    }

    @Test void changedHistoryProducesANativeStaleReplyWithoutCallingTheModel() {
        var old = fixture.fixture.payment(fixture.date, "100");
        var preview = fixture.preview(Kind.PAYMENT, Mode.REPLACE);
        var proposal = message(ASSISTANT, "Применить эти изменения?");
        confirmations.publishProposal(preview.operationId(), preview.revision(), proposal.getId());
        var yes = message(USER, "Да");
        confirmations.captureObservation(fixture.conversation, yes.getId(), proposal.getId(), preview.revision());
        old.setAmount(new java.math.BigDecimal("101"));
        fixture.data.saveWithoutReload(old);
        var ui = data.create(AiChatMessage.class);
        ui.setId(yes.getId());
        assertThat(chat.processMessage(ui)).contains("устарел");
        assertThat(confirmations.pendingProposal(fixture.conversation)).isEmpty();
        fixture.assertPending(preview);
    }

    @Test void actualObservedUserAppliesOnceWithoutAModelAndRestoresTheSameReceipt() {
        var preview = fixture.preview(Kind.SHIPMENT, Mode.FILL);
        var proposal = message(ASSISTANT, "График отгрузки: 2026-06-01 — 120; 2026-06-02 — 80. Применить эти изменения?");
        confirmations.publishProposal(preview.operationId(), preview.revision(), proposal.getId());
        var yes = message(USER, "Да");
        confirmations.captureObservation(fixture.conversation, yes.getId(), proposal.getId(), preview.revision());
        var ui = data.create(AiChatMessage.class);
        ui.setId(yes.getId());
        ui.setContent("Нет"); // Only persisted content is authoritative.
        String saved = chat.processMessage(ui);
        assertThat(saved).contains("Сохранено", "200", "2");
        assertThat(chat.processMessage(ui)).isEqualTo(saved);
        assertThat(data.load(AiChatMessageEntity.class).query("e.conversation.id=:c and e.content=:receipt")
                .parameter("c", fixture.conversation).parameter("receipt", saved).list()).hasSize(1);
        var operation = fixture.store.load(preview.operationId());
        fixture.assertRows(fixture.items(Kind.SHIPMENT, operation.resultId(), false));
    }
}
