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
    @Autowired org.springframework.core.env.Environment environment;

    @Test
    void configuredModelRequestOmitsReasoningEffort() {
        var options = chatModel.getOptions();
        assertThat(options.getModel()).isEqualTo(environment.getRequiredProperty("OPENAI_MODEL"));
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
        assertThat(text).contains("independently for each project and schedule kind", "effectiveDate <= reportDate",
                "partial name", "ask the user to clarify", "Never invent a currency",
                "latest non-empty correction", "Empty corrections do not erase earlier non-empty snapshots");
        assertThat(new org.springframework.ai.chat.prompt.PromptTemplate(text)
                .render(java.util.Map.of("responseLanguage", "Russian", "additionalInstructions", "")))
                .contains("Russian", "finassist_getEntityLinks");
    }

    @Test
    void registersNavigationTool() {
        assertThat(context.containsBean("finassist_EntityLinksTool")).isTrue();
    }
}
