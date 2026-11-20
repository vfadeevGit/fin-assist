package ru.stnovator.finassist.ai;

import io.jmix.aitoolsflowuidata.entity.*;
import io.jmix.core.*;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import io.jmix.aitools.tool.AiToolStatusPublisher;
import ru.stnovator.finassist.ai.schedule.*;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.test_support.DashboardFixtures;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class ScheduleAiChatDataServiceTest {
    @Autowired ScheduleAiChatDataService service;
    @Autowired ScheduleImportWorkflow workflow;
    @Autowired ScheduleConversationConfirmationService confirmations;
    @Autowired UnconstrainedDataManager data;
    @Autowired SystemAuthenticator auth;
    @Autowired ScheduleImportStore store;
    @Autowired DataManager secured;
    @Autowired ScheduleImportTool imports;
    @Autowired ScheduleAttachmentService attachments;
    @Autowired FileStorageLocator storages;
    UUID conversation,message;Object originalClient;
    final String owner="chat-"+UUID.randomUUID();
    @BeforeEach void setup(){auth.begin("admin");var c=data.create(AiConversationEntity.class);c.setUsername(owner);c.setTitle("chat");conversation=data.save(c).getId();var u=data.create(User.class);u.setUsername(owner);DashboardFixtures.authenticate(u,"ROLE_finance-manager-role");message=message("current input");originalClient=ReflectionTestUtils.getField(service,"chatClient");}
    UUID message(String content){var m=data.create(AiChatMessageEntity.class);m.setConversation(data.load(AiConversationEntity.class).id(conversation).one());m.setType(AiChatMessageEntityType.USER);m.setContent(content);return data.save(m).getId();}
    @AfterEach void cleanup(){ReflectionTestUtils.setField(service,"chatClient",originalClient);data.load(AiScheduleImport.class).all().list().stream().filter(e->owner.equals(e.getOwner())).forEach(data::remove);data.load(AiScheduleAttachment.class).all().list().stream().filter(e->owner.equals(e.getOwner())).forEach(e->{storages.getByName(e.getFileRef().getStorageName()).removeFile(e.getFileRef());data.remove(e);});data.load(AiChatMessageEntity.class).query("select e from aitls_AiChatMessageEntity e where e.conversation.id=:c").parameter("c",conversation).list().forEach(data::remove);data.load(AiConversationEntity.class).id(conversation).optional().ifPresent(data::remove);auth.end();}
    @Test void stateSurvivesHistoryWindow() {
        var draft=workflow.start(conversation,ScheduleImportTypes.Kind.PAYMENT);workflow.setRows(draft.operationId(),draft.revision(),List.of(new ScheduleImportTypes.InputRow("2026-06-01","120","input")),null);
        message("newer overlapping input");
        var original=ReflectionTestUtils.getField(service,"dataProperties");
        List<org.springframework.ai.chat.messages.Message> history;
        try {
            ReflectionTestUtils.setField(service,"dataProperties",new io.jmix.aitoolsflowuidata.AiToolsFlowuiDataProperties(true,1));
            history=service.loadHistory(conversation,data.load(AiChatMessageEntity.class).id(message).one());
        } finally { ReflectionTestUtils.setField(service,"dataProperties",original); }
        assertThat(history.stream().map(org.springframework.ai.chat.messages.Message::getText)).anySatisfy(text->assertThat(text).contains(draftId(),"rowCount","120"));
        assertThat(history.stream().map(org.springframework.ai.chat.messages.Message::getText)).contains("current input").doesNotContain("newer overlapping input");
        assertThat(history.stream().map(org.springframework.ai.chat.messages.Message::getMetadata)).anySatisfy(meta->assertThat(meta.get("finassist_currentUserMessageId")).isEqualTo(message));
    }
    @Test void currentSubmissionMetadataIsPresentBeforeDraftAndFutureFilesAreAbsent() {
        var file=attachments.stage(conversation,"current-plan.csv",new java.io.ByteArrayInputStream("date;amount\n2026-06-01;120".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        attachments.bindToMessage(file,message);
        var later=message("later input");
        var future=attachments.stage(conversation,"future-plan.csv",new java.io.ByteArrayInputStream(new byte[0]));attachments.bindToMessage(future,later);
        var history=service.loadHistory(conversation,data.load(AiChatMessageEntity.class).id(message).one());
        String text=history.stream().map(org.springframework.ai.chat.messages.Message::getText).collect(java.util.stream.Collectors.joining());
        assertThat(text).contains("current-plan.csv", file.toString(), "sizeBytes", "available").doesNotContain("future-plan.csv",future.toString());
        assertThat(data.load(AiScheduleImport.class).all().list().stream().filter(e->owner.equals(e.getOwner()))).isEmpty();
    }
    @Test void historicalAttachmentAppearsOutsideTextMemoryWithoutReadingCells() {
        var file=attachments.stage(conversation,"historical-plan.csv",
                new java.io.ByteArrayInputStream("private file cells".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        attachments.bindToMessage(file,message);
        var request=message("Use the file I sent earlier");
        var original=ReflectionTestUtils.getField(service,"dataProperties");
        try {
            ReflectionTestUtils.setField(service,"dataProperties",new io.jmix.aitoolsflowuidata.AiToolsFlowuiDataProperties(true,1));
            var history=service.loadHistory(conversation,data.load(AiChatMessageEntity.class).id(request).one());
            assertThat(history).extracting(org.springframework.ai.chat.messages.Message::getText)
                    .anySatisfy(text->assertThat(text).contains(file.toString(),"historical-plan.csv").doesNotContain("private file cells"))
                    .doesNotContain("current input");
        } finally {
            ReflectionTestUtils.setField(service,"dataProperties",original);
        }
    }
    String draftId(){return data.load(AiScheduleImport.class).all().list().stream().filter(e->owner.equals(e.getOwner())).findFirst().orElseThrow().getId().toString();}
    @Test void normalChatStillUsesDefaultHistoryAndCallbacks() {
        var updates=new ArrayList<io.jmix.aitools.tool.AiToolStatusUpdate>();
        ChatModel model=mock(ChatModel.class);when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(invocation->{
            Prompt prompt=invocation.getArgument(0);assertThat(prompt.getContents()).contains("current input","Захар","finassist_getEntityLinks");
            var options=(ToolCallingChatOptions)Objects.requireNonNull(prompt.getOptions());
            var context=Objects.requireNonNull(options.getToolContext());
            assertThat(context).containsEntry("finassist_currentUserMessageId",message).containsKey("assistantMessageId").containsKey(AiToolStatusPublisher.STATUS_UPDATE_CALLBACK);
            new AiToolStatusPublisher().update("progress",new ToolContext(context));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Ready"))));});
        ReflectionTestUtils.setField(service,"chatClient",ChatClient.builder(model).build());
        var ui=data.create(io.jmix.aitoolsflowui.model.AiChatMessage.class);ui.setId(message);
        assertThat(service.processMessage(ui,updates::add)).isEqualTo("Ready");assertThat(updates).hasSize(1);
        assertThat(data.load(AiChatMessageEntity.class).query("select e from aitls_AiChatMessageEntity e where e.conversation.id=:c and e.type=:type").parameter("c",conversation).parameter("type",AiChatMessageEntityType.ASSISTANT.getId()).list()).singleElement().extracting(AiChatMessageEntity::getContent).isEqualTo("Ready");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"removed","hidden","reassigned"})
    void invalidSelectionDoesNotBlockOrdinaryChatOrCancellation(String invalidation) {
        try(var target=new ru.stnovator.finassist.test_support.RelatedFixtures(secured,auth);
            var fixture=new ru.stnovator.finassist.test_support.RelatedFixtures(secured,auth)) {
            var draft=workflow.start(conversation,ScheduleImportTypes.Kind.PAYMENT);
            draft=workflow.setRows(draft.operationId(),draft.revision(),List.of(new ScheduleImportTypes.InputRow("2026-06-01","120","input")),null);
            workflow.selectContract(draft.operationId(),draft.revision(),fixture.contract.getId());draft=store.load(draft.operationId());
            var preview=workflow.preview(draft.operationId(),draft.revision(),ScheduleImportTypes.Mode.FILL,null,null);
            if(invalidation.equals("removed"))secured.remove(fixture.project);
            else if(invalidation.equals("hidden")){var u=data.create(User.class);u.setUsername(owner);DashboardFixtures.restricted(u,"chat-deny-project");}
            else {var project=secured.load(Project.class).id(fixture.project.getId()).one();project.setContract(target.contract);secured.saveWithoutReload(project);}
            var state=imports.conversationState(conversation).getFirst();assertThat(state.nextRequired()).isEqualTo("SELECTION_UNAVAILABLE");
            assertThat(state.contractId()).isNull();assertThat(state.projectId()).isNull();assertThat(state.mode()).isNull();assertThat(state.modes()).isEmpty();assertThat(state.total()).isNull();assertThat(state.addendumNumber()).isNull();assertThat(state.effectiveDate()).isNull();
            assertThat(state.kind()).isNull();assertThat(state.source()).isNull();assertThat(state.sourceMessageId()).isNull();assertThat(state.mapping()).isNull();assertThat(state.rowCount()).isZero();
            ChatModel model=mock(ChatModel.class);when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
            when(model.call(any(Prompt.class))).thenAnswer(invocation->{Prompt prompt=invocation.getArgument(0);assertThat(prompt.getContents()).contains("current input","SELECTION_UNAVAILABLE").doesNotContain(fixture.contract.getId().toString(),fixture.project.getId().toString());return new ChatResponse(List.of(new Generation(new AssistantMessage("Ready"))));});
            ReflectionTestUtils.setField(service,"chatClient",ChatClient.builder(model).build());var ui=data.create(io.jmix.aitoolsflowui.model.AiChatMessage.class);ui.setId(message);
            assertThat(service.processMessage(ui)).isEqualTo("Ready");
            var assistant=data.load(AiChatMessageEntity.class).query("select e from aitls_AiChatMessageEntity e where e.conversation.id=:c and e.type=:type").parameter("c",conversation).parameter("type",AiChatMessageEntityType.ASSISTANT.getId()).one();
            var context=new ToolContext(Map.of("assistantMessageId",assistant.getId(),ScheduleChatContext.USER_MESSAGE_ID,message));
            UUID operation=preview.operationId();long revision=preview.revision();
            assertThatThrownBy(()->imports.previewScheduleImport(operation,revision,ScheduleImportTypes.Mode.FILL,null,null,context)).isInstanceOf(RuntimeException.class);
            var cancelled=imports.cancelScheduleImport(operation,revision,context).state();assertThat(cancelled.nextRequired()).isEqualTo("CLOSED");assertThat(cancelled.contractId()).isNull();assertThat(cancelled.projectId()).isNull();
        }
    }
    @Test void substitutedUserUsesNativeChatOwnershipAndTechnicalConstraints() {
        var effective=data.create(User.class);effective.setUsername(owner);var operator=data.create(User.class);operator.setUsername("operator-"+UUID.randomUUID());DashboardFixtures.authenticate(operator,"ROLE_system-full-access");
        var original=Objects.requireNonNull(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
        var token=new io.jmix.core.security.impl.SubstitutedUserAuthenticationToken(original,effective,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_finance-manager-role")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(token);
        ChatModel model=mock(ChatModel.class);when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Ready")))));
        ReflectionTestUtils.setField(service,"chatClient",ChatClient.builder(model).build());var ui=data.create(io.jmix.aitoolsflowui.model.AiChatMessage.class);ui.setId(message);
        assertThat(service.processMessage(ui)).isEqualTo("Ready");
        var assistant=data.load(AiChatMessageEntity.class).query("select e from aitls_AiChatMessageEntity e where e.conversation.id=:c and e.type=:type").parameter("c",conversation).parameter("type",AiChatMessageEntityType.ASSISTANT.getId()).one();
        var result=imports.startScheduleImport(ScheduleImportTypes.Kind.PAYMENT,new ToolContext(Map.of("assistantMessageId",assistant.getId(),ScheduleChatContext.USER_MESSAGE_ID,message)));
        UUID operation=result.state().operationId();assertThat(data.load(AiScheduleImport.class).id(operation).one().getOwner()).isEqualTo(owner);assertThat(secured.load(AiScheduleImport.class).id(operation).optional()).isPresent();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(original);
        assertThatThrownBy(()->store.load(operation)).isInstanceOf(SecurityException.class);assertThat(secured.load(AiScheduleImport.class).id(operation).optional()).isEmpty();assertThatThrownBy(()->service.processMessage(ui)).isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
    }
    @io.jmix.security.role.annotation.RowLevelRole(name="Chat deny project",code="chat-deny-project")
    public interface DenyProject {
        @io.jmix.security.role.annotation.JpqlRowLevelPolicy(entityClass=Project.class,where="1=0") void projects();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void mockModelOrchestratesDataContractProjectModeDetailsPreviewWithoutSaving(boolean laterSubmission) {
        try(var fixture=new ru.stnovator.finassist.test_support.RelatedFixtures(secured,auth)) {
            fixture.payment(java.time.LocalDate.of(2026,6,1),"100");
            var initial=domainCounts();var calls=new java.util.concurrent.atomic.AtomicInteger();
            var json=tools.jackson.databind.json.JsonMapper.builder().build();
            ChatModel model=mock(ChatModel.class);when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
            when(model.call(any(Prompt.class))).thenAnswer(invocation->{
                Prompt prompt=invocation.getArgument(0);int step=calls.getAndIncrement();String name;Map<String,Object> arguments=new HashMap<>();
                if(step==0){name="startScheduleImport";arguments.put("kind","PAYMENT");}
                else if(step>=5){assertThat(toolResponses(prompt)).contains("CHAT_PROPOSAL");return new ChatResponse(List.of(new Generation(new AssistantMessage("Preview ready"))));}
                else {
                    var draft=store.load(data.load(AiScheduleImport.class).query("select e from AiScheduleImport e where e.originalConversationId=:c").parameter("c",conversation).one().getId());
                    arguments.put("operationId",draft.operationId());arguments.put("revision",draft.revision());
                    if(step==1){name="setScheduleRows";arguments.put("rows",List.of(Map.of("dateText","2026-06-01","amountText","120","source","model")));}
                    else if(step==2){name="selectScheduleContract";arguments.put("contractId",fixture.contract.getId());}
                    else {name="previewScheduleImport";arguments.put("mode","ADDENDUM");arguments.put("number","new");if(step==4){assertThat(toolResponses(prompt)).contains("ADDENDUM_DETAILS");arguments.put("effectiveDate","2027-01-01");}}
                }
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call_"+step,"function",name,json.writeValueAsString(arguments)))).build())));
            });
            ReflectionTestUtils.setField(service,"chatClient",ChatClient.builder(model).defaultAdvisors(org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.builder().build()).defaultToolContext(Map.of(AiToolStatusPublisher.DEFAULT_TOOL_CONTEXT_MARKER,true)).build());
            var ui=data.create(io.jmix.aitoolsflowui.model.AiChatMessage.class);ui.setId(message);
            if (laterSubmission) {
                message("Изменить запрос");
                for (int attempt = 0; attempt < 2; attempt++) {
                    calls.set(0);
                    assertThatThrownBy(() -> service.processMessage(ui))
                            .isInstanceOf(ConcurrentModificationException.class);
                    assertThat(confirmations.pendingProposal(conversation)).isEmpty();
                    assertThat(data.load(AiChatMessageEntity.class).query("e.conversation.id=:c and e.type=:type")
                            .parameter("c", conversation).parameter("type", AiChatMessageEntityType.ASSISTANT.getId()).list()).isEmpty();
                }
                return;
            }
            String response = service.processMessage(ui, update -> {});
            assertThat(response).contains("2026-06-01", "120", "2027-01-01", "Применить");
            assertThat(calls.get()).isEqualTo(6);
            assertThat(domainCounts()).isEqualTo(initial);
            var proposal = confirmations.pendingProposal(conversation).orElseThrow();
            assertThat(data.load(AiChatMessageEntity.class).id(proposal.messageId()).one().getContent()).isEqualTo(response);

        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void ordinaryTurnThenOneUploadMapsRealXlsThroughCallbacks(boolean historical) throws Exception {
        var ordinary=data.load(AiChatMessageEntity.class).id(message).one();
        ordinary.setContent("Покажи договоры НК НПЗ");data.saveWithoutReload(ordinary);
        byte[] bytes;
        try(var book=new org.apache.poi.hssf.usermodel.HSSFWorkbook();var out=new java.io.ByteArrayOutputStream()) {
            var sheet=book.createSheet("График оплат");var header=sheet.createRow(0);
            header.createCell(0).setCellValue("Дата оплаты");header.createCell(1).setCellValue("Сумма, руб.");
            var dates=List.of("2027-02-15","2026-12-01","2027-01-15","2026-12-01","2027-03-15","2027-01-15","2026-12-15");
            double[] amounts={200000,100000,150000.25,50000,175000,-25000.25,100000};
            for(int i=0;i<dates.size();i++){var row=sheet.createRow(i+1);row.createCell(0).setCellValue(java.time.LocalDate.parse(dates.get(i)));row.createCell(1).setCellValue(amounts[i]);}
            sheet.createRow(8);var total=sheet.createRow(9);total.createCell(0).setCellValue("ИТОГО");total.createCell(1).setCellFormula("SUM(B2:B8)");book.write(out);bytes=out.toByteArray();
        }
        var initial=domainCounts();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        ChatModel model=mock(ChatModel.class);when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(invocation->{
            Prompt prompt=invocation.getArgument(0);int step=calls.getAndIncrement();
            if(step==0){assertThat(prompt.getContents()).contains("Покажи договоры НК НПЗ").doesNotContain("one-upload.xls");return new ChatResponse(List.of(new Generation(new AssistantMessage("Contracts listed"))));}
            String name;Map<String,Object> arguments=new HashMap<>();
            if(step==1){
                assertThat(prompt.getContents()).contains("one-upload.xls", "sizeBytes", "available");
                name="startScheduleImport";arguments.put("kind","PAYMENT");
            } else {
                var response=prompt.getInstructions().stream().filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)
                        .map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast).flatMap(m->m.getResponses().stream()).reduce((a,b)->b).orElseThrow();
                var result=json.readTree(response.responseData());
                assertThat(result.path("issues").size()).isZero();
                if (historical && step==2) {
                    assertThat(result.path("inspection").isNull()).isTrue();
                    var file=data.load(AiScheduleAttachment.class)
                            .query("select e from AiScheduleAttachment e where e.originalConversationId=:c")
                            .parameter("c",conversation).one();
                    name="selectScheduleAttachment";
                    arguments.put("operationId",result.path("state").path("operationId").stringValue());
                    arguments.put("revision",result.path("state").path("revision").asLong());
                    arguments.put("attachmentId",file.getId());
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(
                            List.of(new AssistantMessage.ToolCall("file_"+step,"function",name,json.writeValueAsString(arguments)))).build())));
                }
                if(step==(historical?4:3)){
                    assertThat(result.path("state").path("rowCount").asInt()).isEqualTo(5);
                    assertThat(result.path("state").path("total").decimalValue()).isEqualByComparingTo("750000.00");
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("Five dates prepared"))));
                }
                assertThat(step).isEqualTo(historical?3:2);
                assertThat(result.path("state").path("attachment").path("fileName").asText()).isEqualTo("one-upload.xls");
                var sheets=result.path("inspection").path("sheets");assertThat(sheets.size()).isEqualTo(1);
                var sheet=sheets.get(0);var sample=sheet.path("sample");
                int dateColumn=-1,amountColumn=-1,totalRow=-1;
                for(var cell:sample.get(0)) {
                    if(cell.path("formatted").asText().contains("Дата"))dateColumn=cell.path("column").asInt();
                    if(cell.path("formatted").asText().contains("Сумма"))amountColumn=cell.path("column").asInt();
                }
                for(int row=0;row<sample.size();row++)for(var cell:sample.get(row))
                    if(cell.path("formatted").asText().equals("ИТОГО"))totalRow=row;
                assertThat(dateColumn).isZero();assertThat(amountColumn).isEqualTo(1);assertThat(totalRow).isEqualTo(9);
                name="applyScheduleMapping";
                arguments.put("operationId",result.path("state").path("operationId").asText());
                arguments.put("revision",result.path("state").path("revision").asLong());
                arguments.put("mapping",new ScheduleTableTypes.Mapping(sheet.path("key").asText(),dateColumn,amountColumn,1,Set.of(totalRow),null));
            }
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("file_"+step,"function",name,json.writeValueAsString(arguments)))).build())));
        });
        ReflectionTestUtils.setField(service,"chatClient",ChatClient.builder(model).defaultAdvisors(org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.builder().build()).defaultToolContext(Map.of(AiToolStatusPublisher.DEFAULT_TOOL_CONTEXT_MARKER,true)).build());
        var ui=data.create(io.jmix.aitoolsflowui.model.AiChatMessage.class);ui.setId(message);
        assertThat(service.processMessage(ui)).isEqualTo("Contracts listed");
        message=message(historical?"Placing the file here for later":"Prepare payment schedule from the attached file");
        var file=attachments.stage(conversation,"one-upload.xls",new java.io.ByteArrayInputStream(bytes));attachments.bindToMessage(file,message);
        if (historical) message=message("Prepare payment schedule from the earlier file");
        ui.setId(message);assertThat(service.processMessage(ui,update->{})).isEqualTo("Five dates prepared");
        assertThat(calls.get()).isEqualTo(historical?5:4);assertThat(domainCounts()).isEqualTo(initial);
        var later=message("Continue with contract selection");
        var restored=service.loadHistory(conversation,data.load(AiChatMessageEntity.class).id(later).one());
        assertThat(restored.stream().map(org.springframework.ai.chat.messages.Message::getText)).anySatisfy(text->assertThat(text).contains("one-upload.xls", "750000", "rowCount"));
    }
    String toolResponses(Prompt prompt){return prompt.getInstructions().stream().filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance).map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast).flatMap(m->m.getResponses().stream()).map(org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse::responseData).collect(java.util.stream.Collectors.joining());}
    List<Long> domainCounts(){return java.util.stream.Stream.of("PaymentScheduleItem","ShipmentScheduleItem","Addendum","PaymentScheduleCorrection","PaymentScheduleCorrectionItem","ShipmentScheduleCorrection","ShipmentScheduleCorrectionItem").map(e->data.loadValue("select count(e) from "+e+" e",Long.class).one()).toList();}
}
