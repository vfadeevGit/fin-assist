package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntity;
import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntityType;
import io.jmix.core.FetchPlan;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.usersubstitution.CurrentUserSubstitution;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Resolves per-request server metadata. No model arguments, latest-message lookup or mutable request holder. */
@Service
public class ScheduleChatContext {
    public static final String USER_MESSAGE_ID = "finassist_currentUserMessageId";
    public record Submission(UUID conversationId, UUID userMessageId) {}
    private final UnconstrainedDataManager data;
    private final ScheduleImportOwnership ownership;
    private final CurrentUserSubstitution substitution;
    public ScheduleChatContext(UnconstrainedDataManager data,ScheduleImportOwnership ownership,CurrentUserSubstitution substitution) {
        this.data=data;this.ownership=ownership;this.substitution=substitution;
    }
    public Submission resolve(ToolContext context) {
        if(context==null || !(context.getContext().get("assistantMessageId") instanceof UUID assistantId)
                || !(context.getContext().get(USER_MESSAGE_ID) instanceof UUID userId))throw new SecurityException("Chat submission is unavailable");
        var assistant=message(assistantId);var user=message(userId);
        if(assistant.getType()!=AiChatMessageEntityType.ASSISTANT || user.getType()!=AiChatMessageEntityType.USER
                || !assistant.getConversation().getId().equals(user.getConversation().getId()))throw new SecurityException("Chat submission is unavailable");
        var conversation=user.getConversation();
        ownership.conversation(conversation.getId());
        if(!substitution.getEffectiveUser().getUsername().equals(conversation.getUsername()))throw new SecurityException("Chat submission is unavailable");
        return new Submission(conversation.getId(),userId);
    }
    private AiChatMessageEntity message(UUID id) {
        return data.load(AiChatMessageEntity.class).id(id).fetchPlan(fp->fp.addFetchPlan(FetchPlan.BASE).add("conversation",FetchPlan.BASE))
                .optional().orElseThrow(()->new SecurityException("Chat submission is unavailable"));
    }
}
