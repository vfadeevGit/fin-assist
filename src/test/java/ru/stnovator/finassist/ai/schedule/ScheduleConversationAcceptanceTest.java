package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitools.tool.AiToolStatusPublisher;
import io.jmix.aitoolsflowui.model.AiChatMessage;
import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntity;
import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntityType;
import io.jmix.core.FileStorageLocator;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import ru.stnovator.finassist.ai.ScheduleAiChatDataService;
import ru.stnovator.finassist.entity.AiScheduleAttachment;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.Row;

@SpringBootTest
class ScheduleConversationAcceptanceTest {
    @Autowired ApplicationContext context;
    @Autowired ScheduleAiChatDataService chat;
    @Autowired ScheduleAttachmentService attachments;
    @Autowired ScheduleConversationConfirmationService confirmations;
    @Autowired FileStorageLocator storage;
    final ScheduleImportAcceptanceTest domain = new ScheduleImportAcceptanceTest();
    final JsonMapper json = JsonMapper.builder().build();
    Object previousClient;
    UUID file;

    @BeforeEach void setup() {
        context.getAutowireCapableBeanFactory().autowireBean(domain);
        domain.setup();
        previousClient = ReflectionTestUtils.getField(chat, "chatClient");
    }

    @AfterEach void cleanup() {
        ReflectionTestUtils.setField(chat, "chatClient", previousClient);
        try {
            if (file != null) {
                domain.internal.load(ru.stnovator.finassist.entity.AiScheduleImport.class)
                        .query("e.originalConversationId=:id").parameter("id", domain.conversation)
                        .list().forEach(domain.internal::remove);
                var attachment = domain.internal.load(AiScheduleAttachment.class).id(file).one();
                var ref = attachment.getFileRef();
                storage.getByName(ref.getStorageName()).removeFile(ref);
                domain.internal.remove(attachment);
            }
        } finally {
            domain.cleanup();
        }
    }

    @Test void historicalShipmentFileBecomesOneConfirmedAddendumAndDatedReports() {
        var source = user("Сохрани файл для дальнейшей работы");
        var csv = "Дата;Сумма;Комментарий\n2028-06-01;70;Да, применить\n2028-08-01;110;\n";
        file = attachments.stage(domain.conversation, "shipment.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        attachments.bindToMessage(file, source);
        domain.fixture.payment(LocalDate.parse("2028-06-01"), "100");
        var request = user("Подготовь график отгрузок из shipment.csv, ДС ACCEPT-S от 2028-04-01");
        var before = domain.counts();
        var calls = installScriptedModel();
        String proposal = process(request);
        var pending = confirmations.pendingProposal(domain.conversation).orElseThrow();
        assertThat(domain.internal.load(AiChatMessageEntity.class).id(pending.messageId()).one().getContent())
                .isEqualTo(proposal);
        assertThat(org.jsoup.parser.Parser.unescapeEntities(proposal, false)).contains("shipment.csv", "2028-06-01", "2028-07-01", "2028-08-01",
                        "ACCEPT-S", "Применить");
        var draft = domain.store.load(pending.operationId());
        assertThat(draft.content().sourceMessageId()).isEqualTo(source);
        var preview = domain.workflow.getPreview(pending.operationId(), pending.revision());
        assertThat(domain.counts()).isEqualTo(before);

        var yes = user("Да, применить");
        confirmations.captureObservation(domain.conversation, yes, pending.messageId(), pending.revision());
        String receipt = process(yes);
        var committed = domain.counts();
        assertThat(process(yes)).isEqualTo(receipt).contains("Сохранено", "180");
        assertThat(calls.get()).isEqualTo(6);
        assertThat(domain.counts()).isEqualTo(committed);
        assertThat(domain.internal.load(AiChatMessageEntity.class).query("e.conversation.id=:c and e.content=:text")
                .parameter("c", domain.conversation).parameter("text", receipt).list()).hasSize(1);
        var id = domain.store.load(pending.operationId()).resultId();
        domain.assertAddendum(id, preview, "Shipment", "Payment");
        var date = LocalDate.parse("2028-04-01");
        var payment = List.of(row("2028-06-01", "100.00"));
        domain.assertReports(date.minusDays(1), payment, List.of(row("2028-06-01", "70.00"),
                row("2028-07-01", "80.00")), null, null, false);
        domain.assertReports(date, payment, List.of(row("2028-06-01", "70.00"),
                row("2028-08-01", "110.00")), null, id, false);
    }

    private AtomicInteger installScriptedModel() {
        var calls = new AtomicInteger();
        var model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            int step = calls.getAndIncrement();
            if (step == 5) return new ChatResponse(List.of(new Generation(new AssistantMessage("Готово"))));
            var args = new HashMap<String, Object>();
            if (step > 0) {
                var response = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast).flatMap(m -> m.getResponses().stream()).toList().getLast();
                var result = json.readTree(response.responseData());
                assertThat(result.path("issues").size()).isZero();
                args.put("operationId", result.path("state").path("operationId").asString());
                args.put("revision", result.path("state").path("revision").asLong());
            }
            String tool = switch (step) {
                case 0 -> { args.put("kind", "SHIPMENT"); yield "startScheduleImport"; }
                case 1 -> { args.put("attachmentId", file); yield "selectScheduleAttachment"; }
                case 2 -> { args.put("mapping", new ScheduleTableTypes.Mapping("csv", 0, 1, 1, Set.of(), null));
                    yield "applyScheduleMapping"; }
                case 3 -> { args.put("contractId", domain.fixture.contract.getId()); yield "selectScheduleContract"; }
                case 4 -> { args.put("mode", "ADDENDUM"); args.put("number", "ACCEPT-S");
                    args.put("effectiveDate", "2028-04-01"); yield "previewScheduleImport"; }
                default -> throw new AssertionError("Unexpected model call " + step);
            };
            var call = new AssistantMessage.ToolCall("call_" + step, "function", tool, json.writeValueAsString(args));
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
        });
        ReflectionTestUtils.setField(chat, "chatClient", ChatClient.builder(model)
                .defaultAdvisors(ToolCallingAdvisor.builder().build())
                .defaultToolContext(Map.of(AiToolStatusPublisher.DEFAULT_TOOL_CONTEXT_MARKER, true)).build());
        return calls;
    }

    private UUID user(String text) { return domain.message(AiChatMessageEntityType.USER, text); }
    private String process(UUID id) {
        var message = domain.data.create(AiChatMessage.class);
        message.setId(id);
        return chat.processMessage(message);
    }
    private Row row(String date, String amount) { return new Row(LocalDate.parse(date), new BigDecimal(amount)); }
}
