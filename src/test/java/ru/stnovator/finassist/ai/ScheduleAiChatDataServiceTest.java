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
    @Autowired UnconstrainedDataManager data;
    @Autowired SystemAuthenticator auth;
    @Autowired ScheduleImportStore store;
    @Autowired DataManager secured;
    UUID conversation,message;Object originalClient;
    final String owner="chat-"+UUID.randomUUID();
    @BeforeEach void setup(){auth.begin("admin");var c=data.create(AiConversationEntity.class);c.setUsername(owner);c.setTitle("chat");conversation=data.save(c).getId();var u=data.create(User.class);u.setUsername(owner);DashboardFixtures.authenticate(u,"ROLE_finance-manager-role");message=message("current input");originalClient=ReflectionTestUtils.getField(service,"chatClient");}
    UUID message(String content){var m=data.create(AiChatMessageEntity.class);m.setConversation(data.load(AiConversationEntity.class).id(conversation).one());m.setType(AiChatMessageEntityType.USER);m.setContent(content);return data.save(m).getId();}
    @AfterEach void cleanup(){ReflectionTestUtils.setField(service,"chatClient",originalClient);data.load(AiScheduleImport.class).all().list().stream().filter(e->owner.equals(e.getOwner())).forEach(data::remove);data.load(AiChatMessageEntity.class).query("select e from aitls_AiChatMessageEntity e where e.conversation.id=:c").parameter("c",conversation).list().forEach(data::remove);data.load(AiConversationEntity.class).id(conversation).optional().ifPresent(data::remove);auth.end();}
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
    @Test void mockModelOrchestratesDataContractProjectModeDetailsPreviewWithoutSaving() {
        try(var fixture=new ru.stnovator.finassist.test_support.RelatedFixtures(secured,auth)) {
            fixture.payment(java.time.LocalDate.of(2026,6,1),"100");
            var initial=domainCounts();var calls=new java.util.concurrent.atomic.AtomicInteger();
            var json=tools.jackson.databind.json.JsonMapper.builder().build();
            ChatModel model=mock(ChatModel.class);when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
            when(model.call(any(Prompt.class))).thenAnswer(invocation->{
                Prompt prompt=invocation.getArgument(0);int step=calls.getAndIncrement();String name;Map<String,Object> arguments=new HashMap<>();
                if(step==0){name="startScheduleImport";arguments.put("kind","PAYMENT");}
                else if(step>=5){assertThat(toolResponses(prompt)).contains("UI_CONFIRMATION");return new ChatResponse(List.of(new Generation(new AssistantMessage("Preview ready"))));}
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
            assertThat(service.processMessage(ui,update->{})).isEqualTo("Preview ready");assertThat(calls.get()).isEqualTo(6);assertThat(domainCounts()).isEqualTo(initial);
        }
    }
    String toolResponses(Prompt prompt){return prompt.getInstructions().stream().filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance).map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast).flatMap(m->m.getResponses().stream()).map(org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse::responseData).collect(java.util.stream.Collectors.joining());}
    List<Long> domainCounts(){return java.util.stream.Stream.of("PaymentScheduleItem","ShipmentScheduleItem","Addendum","PaymentScheduleCorrection","PaymentScheduleCorrectionItem","ShipmentScheduleCorrection","ShipmentScheduleCorrectionItem").map(e->data.loadValue("select count(e) from "+e+" e",Long.class).one()).toList();}
}
