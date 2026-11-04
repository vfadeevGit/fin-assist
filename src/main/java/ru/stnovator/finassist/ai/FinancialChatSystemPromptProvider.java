package ru.stnovator.finassist.ai;

import io.jmix.aitoolsflowuidata.service.prompt.AiChatSystemPromptProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Append domain guidance without copying or replacing the framework's evolving instructions. */
@Component
public class FinancialChatSystemPromptProvider implements AiChatSystemPromptProvider {
    private final Resource resource;

    public FinancialChatSystemPromptProvider() throws IOException {
        String framework = new ClassPathResource("io/jmix/aitoolsflowuidata/service/prompt/system-chat-prompt.st")
                .getContentAsString(StandardCharsets.UTF_8);
        String domain = new ClassPathResource("prompts/financial-chat-system-prompt.st")
                .getContentAsString(StandardCharsets.UTF_8);
        resource = new ByteArrayResource((framework + "\n" + domain).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Resource getResource() {
        return resource;
    }
}
