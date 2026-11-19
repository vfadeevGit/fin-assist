package ru.stnovator.finassist.ai.schedule;

import com.sun.net.httpserver.HttpServer;
import io.jmix.aitools.ChatClientFactory;
import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntity;
import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntityType;
import io.jmix.aitoolsflowuidata.entity.AiConversationEntity;
import io.jmix.core.*;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import io.jmix.reports.yarg.structure.BandData;
import org.junit.jupiter.api.*;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.report.*;
import ru.stnovator.finassist.service.EffectiveScheduleService;
import ru.stnovator.finassist.test_support.DashboardFixtures;
import ru.stnovator.finassist.test_support.RelatedFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

/** Joins real preparation, persistence and report datasets; only the model HTTP boundary is scripted. */
@SpringBootTest
class ScheduleImportAcceptanceTest {
    @Autowired ScheduleImportTool tool;
    @Autowired ScheduleImportWorkflow workflow;
    @Autowired ScheduleImportStore store;
    @Autowired ScheduleImportCommitService commits;
    @Autowired EffectiveScheduleService effective;
    @Autowired CashflowCalendar calendar;
    @Autowired ListOfContracts contracts;
    @Autowired DataManager data;
    @Autowired UnconstrainedDataManager internal;
    @Autowired Metadata metadata;
    @Autowired SystemAuthenticator auth;

    private final JsonMapper json = JsonMapper.builder().build();
    private final String owner = "acceptance-" + UUID.randomUUID();
    private final LocalDate june = LocalDate.of(2028, 6, 1);
    private final LocalDate july = june.plusMonths(1);
    private final LocalDate august = june.plusMonths(2);
    private final LocalDate paymentDate = LocalDate.of(2028, 3, 1);
    private final LocalDate shipmentDate = paymentDate.plusMonths(1);
    RelatedFixtures fixture;
    UUID conversation;
    private HttpServer server;

    @BeforeEach
    void setup() {
        auth.begin("admin");
        fixture = new RelatedFixtures(data, auth);
        fixture.contract.setTotalAmount(new BigDecimal("1000.00"));
        fixture.contract.setEndDate(LocalDate.of(2029, 1, 1));
        fixture.contract = data.save(fixture.contract);
        fixture.shipment(june, "70");
        fixture.shipment(july, "80");
        var chat = internal.create(AiConversationEntity.class);
        chat.setUsername(owner);
        chat.setTitle("schedule acceptance");
        conversation = internal.save(chat).getId();
        var user = internal.create(User.class);
        user.setUsername(owner);
        DashboardFixtures.authenticate(user, "ROLE_finance-manager-role");
    }

    @AfterEach
    void cleanup() {
        if (server != null) server.stop(0);
        try {
            auth.runWithUser("admin", () -> {
                internal.load(AiScheduleImport.class).query("select e from AiScheduleImport e where e.owner = :owner")
                        .parameter("owner", owner).list().forEach(internal::remove);
                internal.load(AiChatMessageEntity.class)
                        .query("select e from aitls_AiChatMessageEntity e where e.conversation.id = :id")
                        .parameter("id", conversation).list().forEach(internal::remove);
                internal.load(AiConversationEntity.class).id(conversation).optional().ifPresent(internal::remove);
                // Commit-created children are not registered in RelatedFixtures. Hard-delete in FK order.
                for (String entity : List.of("PaymentScheduleCorrectionItem", "ShipmentScheduleCorrectionItem",
                        "PaymentScheduleCorrection", "ShipmentScheduleCorrection", "PaymentScheduleItem",
                        "ShipmentScheduleItem", "PaymentSchedule", "ShipmentSchedule", "Addendum")) {
                    String path = entity.endsWith("CorrectionItem") ? "correction.project.contract"
                            : entity.endsWith("Item") ? "schedule.project.contract"
                            : entity.equals("Addendum") ? "contract" : "project.contract";
                    internal.load(metadata.getClass(entity).getJavaClass())
                            .query("select e from " + entity + " e where e." + path + ".id = :id")
                            .parameter("id", fixture.contract.getId()).hint(PersistenceHints.SOFT_DELETION, false)
                            .list().forEach(e -> internal.save(new SaveContext().removing(e)
                                    .setHint(PersistenceHints.SOFT_DELETION, false)));
                }
            });
            fixture.close();
        } finally {
            auth.end();
        }
    }

    @Test
    void contractChoicesAndEmptyBaseHistoryRequireExplicitSelectionAndAddendum() {
        var draft = workflow.start(conversation, Kind.PAYMENT);
        assertThat(workflow.findContracts(draft.operationId(), "no-match-" + UUID.randomUUID())).isEmpty();
        assertThat(workflow.findContracts(draft.operationId(), fixture.contract.getInternalID()))
                .extracting(ScheduleImportWorkflow.Choice::id).containsExactly(fixture.contract.getId());
        var second = data.create(Contract.class);
        second.setInternalID(fixture.contract.getInternalID() + "-2");
        second.setCustomer(fixture.customer);
        second.setStartDate(fixture.contract.getStartDate());
        second.setEndDate(fixture.contract.getEndDate());
        second = fixture.save(second);
        assertThat(workflow.findContracts(draft.operationId(), fixture.contract.getInternalID()))
                .extracting(ScheduleImportWorkflow.Choice::id).containsExactlyInAnyOrder(fixture.contract.getId(), second.getId());
        assertThat(store.load(draft.operationId()).content().contractId()).isNull();

        var addendum = data.create(Addendum.class);
        addendum.setContract(fixture.contract); addendum.setNumber("EMPTY-HISTORY"); addendum.setEffectiveDate(paymentDate);
        addendum = fixture.save(addendum);
        var correction = data.create(PaymentScheduleCorrection.class);
        correction.setProject(fixture.project); correction.setAddendum(addendum); fixture.save(correction);
        draft = workflow.setRows(draft.operationId(), draft.revision(),
                List.of(new InputRow(june.toString(), "100", "acceptance")), null);
        workflow.selectContract(draft.operationId(), draft.revision(), fixture.contract.getId());
        var selected = store.load(draft.operationId());
        assertThat(effective.load(fixture.project.getId(), Kind.PAYMENT, null).rows()).isEmpty();
        assertThat(workflow.allowedModes(selected.operationId())).containsExactly(Mode.ADDENDUM);
        var before = counts();
        assertThatThrownBy(() -> workflow.preview(selected.operationId(), selected.revision(), Mode.FILL, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> workflow.preview(selected.operationId(), selected.revision(), Mode.ADDENDUM, "LATER", paymentDate.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        var preview = workflow.preview(selected.operationId(), selected.revision(), Mode.ADDENDUM, "LATER", paymentDate.plusDays(1));
        assertThat(preview.rows()).containsExactly(new Row(june, new BigDecimal("100.00")));
        assertThat(counts()).isEqualTo(before);

        data.remove(data.load(PaymentSchedule.class).id(fixture.payment.getId()).one());
        assertThat(workflow.allowedModes(selected.operationId())).containsExactly(Mode.ADDENDUM);
    }

    @Test
    void paymentThenShipmentAddendumRemainsConsistentEverywhere() throws Exception {
        var paymentBase = rows("100.00", "200.00", july);
        var shipmentBase = rows("70.00", "80.00", july);
        var paymentAfter = rows("100.00", "250.00", august);
        var shipmentAfter = rows("70.00", "110.00", august);

        var fill = prepare(Kind.PAYMENT, Mode.FILL, paymentBase, null, null);
        var savedBase = commits.confirm(fill.operationId(), fill.revision());
        assertThat(savedBase.entityId()).isEqualTo(fixture.payment.getId());
        assertThat(persistedRows("PaymentScheduleItem", "schedule", savedBase.entityId())).isEqualTo(paymentBase);

        var payment = prepare(Kind.PAYMENT, Mode.ADDENDUM, paymentAfter, "ACCEPT-P", paymentDate);
        assertComparison(payment, paymentBase, paymentAfter);
        var paymentSaved = commits.confirm(payment.operationId(), payment.revision());
        assertAddendum(paymentSaved.entityId(), payment, "Payment", "Shipment");

        var shipment = prepare(Kind.SHIPMENT, Mode.ADDENDUM, shipmentAfter, "ACCEPT-S", shipmentDate);
        assertComparison(shipment, shipmentBase, shipmentAfter);
        var shipmentSaved = commits.confirm(shipment.operationId(), shipment.revision());
        assertAddendum(shipmentSaved.entityId(), shipment, "Shipment", "Payment");

        // A later opposite-kind correction must preserve the first full snapshot and both bases.
        assertThat(persistedRows("PaymentScheduleItem", "schedule", fixture.payment.getId())).isEqualTo(paymentBase);
        assertThat(persistedRows("ShipmentScheduleItem", "schedule", fixture.shipment.getId())).isEqualTo(shipmentBase);
        assertThat(effective.load(fixture.project.getId(), Kind.PAYMENT, null).rows()).isEqualTo(payment.rows());
        assertThat(effective.load(fixture.project.getId(), Kind.SHIPMENT, null).rows()).isEqualTo(shipment.rows());
        assertAddendum(paymentSaved.entityId(), payment, "Payment", "Shipment");

        for (LocalDate boundary : List.of(paymentDate.minusDays(1), paymentDate, paymentDate.plusDays(1),
                shipmentDate.minusDays(1), shipmentDate, shipmentDate.plusDays(1))) {
            assertReports(boundary, boundary.isBefore(paymentDate) ? paymentBase : paymentAfter,
                    boundary.isBefore(shipmentDate) ? shipmentBase : shipmentAfter,
                    boundary.isBefore(paymentDate) ? null : paymentSaved.entityId(),
                    boundary.isBefore(shipmentDate) ? null : shipmentSaved.entityId(), false);
        }
        assertReports(shipmentDate.plusDays(1), paymentBase, shipmentBase, null, null, true);

        var before = counts();
        for (Kind kind : Kind.values()) {
            var unchanged = prepare(kind, Mode.ADDENDUM, kind == Kind.PAYMENT ? paymentAfter : shipmentAfter,
                    "SHOULD-NOT-EXIST-" + kind, shipmentDate.plusYears(1));
            assertThat(unchanged.noChanges()).isTrue();
            // AI sees the already recorded future history, while an earlier report still sees the bases.
            assertThat(unchanged.comparison().rows()).allSatisfy(r -> assertThat(r.changed()).isFalse());
            assertThat(unchanged.comparison().beforeTotal()).isEqualByComparingTo(unchanged.total());
        }
        assertReports(paymentDate.minusDays(1), paymentBase, shipmentBase, null, null, false);
        assertThat(commits.confirm(payment.operationId(), payment.revision()))
                .isEqualTo(new ScheduleImportCommitService.CommitResult(payment.operationId(), "Addendum", paymentSaved.entityId(), true));
        assertThat(commits.confirm(shipment.operationId(), shipment.revision()).entityId()).isEqualTo(shipmentSaved.entityId());
        assertThat(counts()).isEqualTo(before);
        var contract = data.load(Contract.class).id(fixture.contract.getId()).one();
        assertThat(contract.getTotalAmount()).isEqualByComparingTo("1000");
        assertThat(contract.getEndDate()).isEqualTo(LocalDate.of(2029, 1, 1));
    }

    private List<Row> rows(String first, String second, LocalDate secondDate) {
        return List.of(new Row(june, new BigDecimal(first)), new Row(secondDate, new BigDecimal(second)));
    }

    private ScheduleImportPreview prepare(Kind kind, Mode mode, List<Row> rows, String number, LocalDate date) throws Exception {
        UUID userMessage = message(AiChatMessageEntityType.USER, kind + " " + rows + " " + number + " " + date);
        UUID assistant = message(AiChatMessageEntityType.ASSISTANT, "");
        var requests = new CopyOnWriteArrayList<JsonNode>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat/completions", exchange -> {
            var request = json.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(request);
            int step = requests.size() - 1;
            var args = new LinkedHashMap<String, Object>();
            String name;
            JsonNode previous = null;
            for (JsonNode m : request.path("messages")) {
                if (m.path("role").asString().equals("tool")) previous = json.readTree(m.path("content").asString());
            }
            if (previous != null) {
                args.put("operationId", previous.path("state").path("operationId").asString());
                args.put("revision", previous.path("state").path("revision").asLong());
            }
            if (step == 0) { name = "startScheduleImport"; args.put("kind", kind.name()); }
            else if (step == 1) {
                name = "setScheduleRows";
                args.put("rows", rows.stream().map(r -> Map.of("dateText", r.date().toString(),
                        "amountText", r.amount().toPlainString(), "source", "untrusted-model-source")).toList());
            } else if (step == 2) { name = "findScheduleContracts"; args.put("query", fixture.contract.getInternalID()); }
            else if (step == 3) { name = "selectScheduleContract"; args.put("contractId", fixture.contract.getId()); }
            else { name = "previewScheduleImport"; args.put("mode", mode.name()); args.put("number", number);
                if (step == 5) args.put("effectiveDate", date == null ? null : date.toString()); }
            int lastTool = mode == Mode.ADDENDUM ? 5 : 4;
            Map<String, Object> reply = step > lastTool ? Map.of("role", "assistant", "content", "Prepared")
                    : Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "call_" + step,
                    "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(args)))));
            byte[] response = json.writeValueAsBytes(Map.of("id", "reply", "object", "chat.completion", "created", 1,
                    "model", "test-model", "choices", List.of(Map.of("index", 0, "message", reply,
                            "finish_reason", step > lastTool ? "stop" : "tool_calls"))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        var before = counts();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(OpenAiChatAutoConfiguration.class,
                            ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
                    .withBean(ChatClientFactory.class)
                    .withPropertyValues("spring.ai.openai.base-url=" + baseUrl, "spring.ai.openai.api-key=test-key",
                            "spring.ai.openai.chat.model=test-model", "spring.ai.openai.max-retries=0", "spring.ai.openai.timeout=5s")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(OpenAiCommonProperties.class).getBaseUrl()).isEqualTo(baseUrl);
                        var reply = context.getBean(ChatClientFactory.class).createChatClientWithDefaultAdvisors().orElseThrow()
                                .prompt("Prepare exactly the schedule supplied in this message; wait for UI confirmation")
                                .tools(tool).toolContext(Map.of("assistantMessageId", assistant,
                                        ScheduleChatContext.USER_MESSAGE_ID, userMessage)).call().content();
                        assertThat(reply).isEqualTo("Prepared");
                    });
        } finally { server.stop(0); server = null; }
        assertThat(requests).hasSize(mode == Mode.ADDENDUM ? 7 : 6);
        assertThat(counts()).as("AI preparation never persists business rows").isEqualTo(before);
        var stored = internal.load(AiScheduleImport.class)
                .query("select e from AiScheduleImport e where e.owner = :owner")
                .parameter("owner", owner).list().stream().map(d -> store.load(d.getId()))
                .filter(d -> userMessage.equals(d.content().sourceMessageId())).findFirst().orElseThrow();
        assertThat(stored.content().projectId()).isEqualTo(fixture.project.getId());
        assertThat(stored.content().rows()).isEqualTo(rows);
        if (stored.status() == ScheduleImportStore.Status.PREVIEW_READY) return workflow.getPreview(stored.operationId(), stored.revision());
        assertThat(requests.getLast().toString()).contains("Изменений нет");
        return workflow.preview(stored.operationId(), stored.revision(), mode, number, date);
    }

    UUID message(AiChatMessageEntityType type, String content) {
        var message = internal.create(AiChatMessageEntity.class);
        message.setConversation(internal.load(AiConversationEntity.class).id(conversation).one());
        message.setType(type); message.setContent(content);
        return internal.save(message).getId();
    }

    private List<Row> persistedRows(String entity, String parent, UUID id) {
        return internal.load(metadata.getClass(entity).getJavaClass())
                .query("select e from " + entity + " e where e." + parent + ".id = :id order by e.itemDate")
                .parameter("id", id).list().stream().map(e -> new Row(EntityValues.getValue(e, "itemDate"),
                        EntityValues.getValue(e, "amount"))).toList();
    }

    private void assertComparison(ScheduleImportPreview preview, List<Row> before, List<Row> after) {
        assertThat(preview.rows()).isEqualTo(after);
        assertThat(preview.comparison().beforeTotal()).isEqualByComparingTo(total(before));
        assertThat(preview.comparison().afterTotal()).isEqualByComparingTo(total(after));
        assertThat(preview.comparison().rows()).hasSize(3).anySatisfy(r -> {
            assertThat(r.date()).isEqualTo(june); assertThat(r.changed()).isFalse();
        }).anySatisfy(r -> { assertThat(r.date()).isEqualTo(july); assertThat(r.after()).isNull(); })
                .anySatisfy(r -> { assertThat(r.date()).isEqualTo(august); assertThat(r.before()).isNull(); });
    }

    void assertAddendum(UUID id, ScheduleImportPreview preview, String kind, String opposite) {
        var addendum = data.load(Addendum.class).id(id).one();
        assertThat(addendum.getNumber()).isEqualTo(preview.number());
        assertThat(addendum.getEffectiveDate()).isEqualTo(preview.effectiveDate());
        assertThat(addendum.getAmountAdjustment()).isNull(); assertThat(addendum.getNewEndDate()).isNull();
        var corrections = internal.load(metadata.getClass(kind + "ScheduleCorrection").getJavaClass())
                .query("select e from " + kind + "ScheduleCorrection e where e.addendum.id = :id").parameter("id", id).list();
        assertThat(corrections).hasSize(1);
        assertThat(persistedRows(kind + "ScheduleCorrectionItem", "correction", (UUID) EntityValues.getId(corrections.getFirst())))
                .as("full snapshot includes unchanged June row").isEqualTo(preview.rows());
        assertThat(internal.load(metadata.getClass(opposite + "ScheduleCorrection").getJavaClass())
                .query("select e from " + opposite + "ScheduleCorrection e where e.addendum.id = :id").parameter("id", id).list()).isEmpty();
    }

    void assertReports(LocalDate boundary, List<Row> payment, List<Row> shipment, UUID paymentId, UUID shipmentId, boolean base) {
        var params = new HashMap<String, Object>(Map.of("reportDate", boundary, "customer", fixture.customer,
                "contract", fixture.contract, "project", fixture.project,
                "calendarMode", base ? CashflowCalendarMode.BASE : CashflowCalendarMode.ACTUAL_WITH_ADDENDUMS,
                "contractListMode", base ? ContractListMode.BASE : ContractListMode.ACTUAL_WITH_ADDENDUMS));
        var customer = new BandData("Customer"); customer.addData("customerId", fixture.customer.getId());
        var contract = new BandData("Contract");
        contracts.contractsDataLoader().loadData(null, customer, params).getFirst().forEach(contract::addData);
        var project = contracts.projectsDataLoader().loadData(null, contract, params).getFirst();
        assertThat(project.get("paymentAddendumId")).isEqualTo(paymentId);
        assertThat(project.get("shipmentAddendumId")).isEqualTo(shipmentId);
        var band = new BandData("Project"); project.forEach(band::addData);
        var records = contracts.projectCashflowRecordsDataLoader().loadData(null, band, params);
        var totals = contracts.projectCashflowTotalsDataLoader().loadData(null, band, params).getFirst();
        assertThat((BigDecimal) totals.get("paymentTotal")).isEqualByComparingTo(total(payment));
        assertThat((BigDecimal) totals.get("shipmentTotal")).isEqualByComparingTo(total(shipment));
        var dates = new TreeSet<LocalDate>(); payment.forEach(r -> dates.add(r.date())); shipment.forEach(r -> dates.add(r.date()));
        assertThat(records).extracting(r -> r.get("cashflowDate")).containsExactlyElementsOf(dates);
        for (var record : records) {
            var date = (LocalDate) record.get("cashflowDate");
            assertThat((BigDecimal) record.get("paymentSum")).isEqualByComparingTo(amount(payment, date));
            assertThat((BigDecimal) record.get("shipmentSum")).isEqualByComparingTo(amount(shipment, date));
        }
        var master = calendar.calendarMasterDataLoader().loadData(null, null, params);
        assertThat(master).hasSize(2);
        params.put("Calendar_master_data", master);
        params.put("Calendar_dynamic_header", calendar.calendarDynamicHeaderDataLoader().loadData(null, null, params));
        var cells = calendar.calendarDataLoader().loadData(null, null, params);
        var calendarTotals = calendar.calendarTotalsDataLoader().loadData(null, null, params);
        for (Kind kind : Kind.values()) {
            var expected = kind == Kind.PAYMENT ? payment : shipment;
            UUID source = kind == Kind.PAYMENT ? paymentId : shipmentId;
            String rowId = fixture.project.getId() + ":" + kind;
            var row = master.stream().filter(r -> rowId.equals(r.get("rowId"))).findFirst().orElseThrow();
            assertThat((BigDecimal) row.get("rowTotal")).isEqualByComparingTo(total(expected));
            assertThat(row.get("planSource")).isEqualTo(source == null ? "База" : "Доп. соглашение");
            if (source != null) {
                String number = kind == Kind.PAYMENT ? "ACCEPT-P" : "ACCEPT-S";
                assertThat((String) row.get("addendumInfo")).contains(number);
                assertThat((String) project.get("cashflowSource")).contains(number);
            }
            var kindCells = cells.stream().filter(c -> rowId.equals(c.get("Calendar_master_data@rowId"))).toList();
            assertThat(kindCells).hasSize(12);
            for (var cell : kindCells) {
                var month = java.time.YearMonth.parse((String) cell.get("Calendar_dynamic_header@monthId"));
                var expectedMonth = expected.stream().filter(r -> java.time.YearMonth.from(r.date()).equals(month)).toList();
                assertThat((BigDecimal) cell.get("amount")).isEqualByComparingTo(total(expectedMonth));
            }
            int index = kind == Kind.SHIPMENT ? 0 : 1;
            assertThat((BigDecimal) calendarTotals.get(index).get("rowTotal")).isEqualByComparingTo(total(expected));
        }
    }

    private BigDecimal amount(List<Row> rows, LocalDate date) {
        return rows.stream().filter(r -> r.date().equals(date)).map(Row::amount).findFirst().orElse(BigDecimal.ZERO);
    }
    private BigDecimal total(List<Row> rows) { return rows.stream().map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add); }
    List<Long> counts() {
        return java.util.stream.Stream.of("PaymentScheduleItem", "ShipmentScheduleItem", "Addendum", "PaymentScheduleCorrection",
                        "ShipmentScheduleCorrection", "PaymentScheduleCorrectionItem", "ShipmentScheduleCorrectionItem")
                .map(e -> internal.loadValue("select count(e) from " + e + " e", Long.class).one()).toList();
    }
}
