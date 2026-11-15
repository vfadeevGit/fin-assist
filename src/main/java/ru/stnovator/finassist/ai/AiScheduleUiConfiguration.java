package ru.stnovator.finassist.ai;

import io.jmix.aitoolsflowui.view.chat.AiChatFragment;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubFragment;
import io.jmix.aitoolsflowui.view.input.AiChatInputFragment;
import io.jmix.flowui.sys.registration.ComponentRegistration;
import io.jmix.flowui.sys.registration.ComponentRegistrationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.stnovator.finassist.ai.view.*;

@Configuration
public class AiScheduleUiConfiguration {
    @Bean
    ComponentRegistration scheduleChatFragment() {
        return ComponentRegistrationBuilder.create(ScheduleAiChatFragment.class).replaceComponent(AiChatFragment.class).build();
    }

    @Bean
    ComponentRegistration scheduleChatInputFragment() {
        return ComponentRegistrationBuilder.create(ScheduleAiChatInputFragment.class).replaceComponent(AiChatInputFragment.class).build();
    }

    @Bean
    ComponentRegistration scheduleChatHubFragment() {
        return ComponentRegistrationBuilder.create(ScheduleAiChatHubFragment.class).replaceComponent(AiChatHubFragment.class).build();
    }
}
