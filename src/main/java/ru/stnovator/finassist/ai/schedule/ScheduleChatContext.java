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
    public static final String PREVIEW_OUTPUT = "finassist_previewOutput";

    /** A new instance is attached to each native model call. Only the preview tool writes it. */
    public static final class PreviewOutput {
        public record Candidate(UUID operationId, long revision, ScheduleImportTypes.Mode mode, boolean noChanges) {}
        private Candidate preview;
        public synchronized void prepared(Candidate value) { preview = value; }
        public synchronized Candidate get() { return preview; }
    }

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
    /** Same persisted USER ordering as the native request's bounded history. */
    public boolean sourceVisible(UUID sourceMessageId,Submission submission) {
        if(sourceMessageId==null)return true;
        var source=ownership.userMessage(sourceMessageId,submission.conversationId());
        var current=ownership.userMessage(submission.userMessageId(),submission.conversationId());
        return atOrBefore(source,current);
    }
    static boolean atOrBefore(AiChatMessageEntity source,AiChatMessageEntity current) {
        var sourceTime=source.getCreatedDate();
        var currentTime=current.getCreatedDate();
        if (sourceTime==null || currentTime==null) return false;
        int order=sourceTime.toInstant().compareTo(currentTime.toInstant());
        return order<0 || order==0 && source.getId().toString().compareTo(current.getId().toString())<=0;
    }
    private AiChatMessageEntity message(UUID id) {
        return data.load(AiChatMessageEntity.class).id(id).fetchPlan(fp->fp.addFetchPlan(FetchPlan.BASE).add("conversation",FetchPlan.BASE))
                .optional().orElseThrow(()->new SecurityException("Chat submission is unavailable"));
    }
}
