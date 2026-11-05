package ru.stnovator.finassist.ai;

import io.jmix.aitools.dataload.introspection.AvailableEntityService;
import io.jmix.aitoolsflowuidata.service.prompt.AiChatSystemPromptProvider;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AiToolsConfigurationTest {
    @Autowired AvailableEntityService entities;
    @Autowired SystemAuthenticator authenticator;
    @Autowired AiChatSystemPromptProvider prompt;
    @Autowired ApplicationContext context;
    @Autowired org.springframework.ai.openai.OpenAiChatModel chatModel;

    @Test
    void defaultQwenFlashRequestOmitsReasoningEffort() {
        var options = chatModel.getOptions();
        assertThat(options.getModel()).isEqualTo("qwen/qwen3.8-flash");
        var request = org.springframework.test.util.ReflectionTestUtils
                .<com.openai.models.chat.completions.ChatCompletionCreateParams>invokeMethod(
                        chatModel, "createRequest",
                        new org.springframework.ai.chat.prompt.Prompt("Hello", options), false);
        assertThat(request).isNotNull();
        assertThat(request.reasoningEffort()).isEmpty();
    }

    @Test
    void excludesInternalEntitiesEvenForAdministrators() {
        authenticator.runWithUser("admin", () -> {
            assertThat(entities.findEntityDescriptorsByNames(List.of("User"))).isEmpty();
            assertThat(entities.findEntityDescriptorsByNames(List.of("Customer", "Contract", "Project"))).hasSize(3);
            assertThat(entities.getEntitySummaries()).hasSize(13);
        });
    }

    @Test
    void extendsFrameworkPromptWithFinancialRules() throws Exception {
        String text = prompt.getResource().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).contains("{responseLanguage}", "{additionalInstructions}",
                "finassist_getEntityLinks", "ACTUAL_WITH_ADDENDUMS", "planned");
        String framework = new org.springframework.core.io.ClassPathResource(
                "io/jmix/aitoolsflowuidata/service/prompt/system-chat-prompt.st")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).startsWith(framework);
        assertThat(text).contains("Both payment and shipment", "falls back to BOTH base schedules",
                "partial name", "ask the user to clarify", "Never invent a currency",
                "represented by at least one visible correction item", "no correction items are ignored");
        assertThat(new org.springframework.ai.chat.prompt.PromptTemplate(text)
                .render(java.util.Map.of("responseLanguage", "Russian", "additionalInstructions", "")))
                .contains("Russian", "finassist_getEntityLinks");
    }

    @Test
    void registersNavigationTool() {
        assertThat(context.containsBean("finassist_EntityLinksTool")).isTrue();
    }
}
