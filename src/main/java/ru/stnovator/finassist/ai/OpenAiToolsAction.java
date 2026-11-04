package ru.stnovator.finassist.ai;

import io.jmix.aitoolsflowui.view.chathub.AiChatHubView;
import io.jmix.core.AccessManager;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.action.ActionType;
import io.jmix.flowui.action.view.ViewAction;
import io.jmix.flowui.view.View;
import org.springframework.beans.factory.annotation.Autowired;

/** Shared toolbar shortcut; navigation never submits a prompt or creates a conversation. */
@ActionType(OpenAiToolsAction.ID)
public class OpenAiToolsAction extends ViewAction<OpenAiToolsAction, View<?>> {
    public static final String ID = "finassist_openAiTools";

    @Autowired
    private AccessManager accessManager;
    @Autowired
    private ViewNavigators viewNavigators;

    public OpenAiToolsAction() {
        this(ID);
    }

    public OpenAiToolsAction(String id) {
        super(id);
    }

    @Override
    protected boolean isPermitted() {
        if (accessManager == null) return false;
        UiShowViewContext context = new UiShowViewContext("aitls_AiChatHubView");
        accessManager.applyRegisteredConstraints(context);
        return context.isPermitted();
    }

    @Override
    public void execute() {
        checkTarget();
        if (isEnabled() && isPermitted()) {
            viewNavigators.view(target, AiChatHubView.class).navigate();
        }
    }
}
