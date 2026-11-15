package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitoolsflowuidata.entity.AiConversationEntity;
import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntity;
import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntityType;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** The effective authentication and persisted conversation are the ownership authority. */
@Component
public class ScheduleImportOwnership {
    private final UnconstrainedDataManager data;
    private final CurrentAuthentication authentication;
    public ScheduleImportOwnership(UnconstrainedDataManager data, CurrentAuthentication authentication) {
        this.data = data; this.authentication = authentication;
    }
    public String username() { return authentication.getUser().getUsername(); }
    public AiConversationEntity conversation(UUID id) {
        var conversation = data.load(AiConversationEntity.class).id(id).optional()
                .orElseThrow(() -> new SecurityException("Conversation is unavailable"));
        if (!username().equals(conversation.getUsername())) throw new SecurityException("Conversation is unavailable");
        return conversation;
    }
    public void userMessage(UUID id, UUID conversationId) {
        var message=data.load(AiChatMessageEntity.class).id(id).optional()
                .orElseThrow(()->new SecurityException("Source message is unavailable"));
        if(!message.getConversation().getId().equals(conversationId) || message.getType()!=AiChatMessageEntityType.USER)
            throw new SecurityException("Source message is unavailable");
        conversation(conversationId);
    }
    public void check(String owner, UUID conversationId) {
        if (!username().equals(owner)) throw new SecurityException("Import is unavailable");
        conversation(conversationId);
    }
    /** A deleted conversation leaves only a server-owned, read-only operation receipt. */
    public void receipt(String owner, UUID originalConversationId) {
        if (!username().equals(owner)) throw new SecurityException("Import is unavailable");
        data.load(AiConversationEntity.class).id(originalConversationId).optional().ifPresent(conversation -> {
            if (!username().equals(conversation.getUsername())) throw new SecurityException("Import is unavailable");
        });
    }
    public boolean owns(String owner, UUID conversationId) {
        try { receipt(owner, conversationId); return true; }
        catch (SecurityException ex) { return false; }
    }
}
