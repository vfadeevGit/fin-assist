package ru.stnovator.finassist.ai.view;

import io.jmix.aitoolsflowui.view.chat.AiChatView;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubFragment;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.fragment.FragmentDescriptor;

/**
 * Retains the native hub, route and history; only carries its first pending attachment.
 */
@FragmentDescriptor("/io/jmix/aitoolsflowui/view/chathub/ai-chat-hub-fragment.xml")
public class ScheduleAiChatHubFragment extends AiChatHubFragment {
    @Override
    protected void startConversation(String prompt) {
        var composer = (ScheduleAiChatInputFragment) composerFragment;
        var conversation = composer.getAttachmentConversation();
        if (conversation == null) {
            super.startConversation(prompt);
            return;
        }
        composer.setInputEnabled(false);
        try {
            viewNavigators.view(UiComponentUtils.getView(this), AiChatView.class)
                    .withRouteParameters(routeSupport.createRouteParameters(AiChatView.ROUTE_PARAM_ID, conversation.getId()))
                    .withAfterNavigationHandler(e -> {
                        var chat = (ScheduleAiChatFragment) UiComponentUtils.findComponent(e.getView(), "chatFragment").orElseThrow();
                        var pending = composer.takePendingAttachment();
                        if (pending != null) chat.acceptPendingAttachment(pending);
                        e.getView().sendInitialPrompt(prompt);
                    }).navigate();
        }
        catch (RuntimeException failure) {
            composer.setInputEnabled(true);
            throw failure;
        }
    }
}
