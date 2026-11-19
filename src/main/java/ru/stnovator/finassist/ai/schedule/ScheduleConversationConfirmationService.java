package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntity;
import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntityType;
import io.jmix.core.UnconstrainedDataManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.stnovator.finassist.entity.AiScheduleImport;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.ConcurrentModificationException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static io.jmix.aitoolsflowuidata.entity.AiChatMessageEntityType.*;

/** Server-only lifecycle. Serialize conversation, then import, then domain contract. */
@Service
@Transactional
public class ScheduleConversationConfirmationService {
    public enum Outcome { APPLIED, DECLINED, NEEDS_DIALOGUE, STALE, NONE }
    public record ReplyOutcome(Outcome outcome, ScheduleImportCommitService.CommitResult result) {
        ReplyOutcome(Outcome outcome) { this(outcome, null); }
    }
    public record Proposal(UUID messageId, UUID operationId, long revision, String fingerprint) {}
    private record Observation(UUID proposalId, Long revision, String outcome) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate reserveConsent;
    private final UnconstrainedDataManager data;
    private final ScheduleImportOwnership ownership;
    private final ScheduleImportStore store;
    private final ScheduleImportCommitService commits;

    public ScheduleConversationConfirmationService(DataSource source, PlatformTransactionManager transactions, UnconstrainedDataManager data,
            ScheduleImportOwnership ownership, ScheduleImportStore store, ScheduleImportCommitService commits) {
        jdbc = new JdbcTemplate(source);
        reserveConsent = new TransactionTemplate(transactions);
        reserveConsent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.data = data;
        this.ownership = ownership;
        this.store = store;
        this.commits = commits;
    }

    public void publishProposal(UUID operationId, long revision, UUID proposalMessageId) {
        var conversationId = data.load(AiScheduleImport.class).id(operationId)
                .one().getOriginalConversationId();
        lockConversation(conversationId);
        var assistant = message(conversationId, proposalMessageId, ASSISTANT);
        if (assistant.getContent() == null || assistant.getContent().isBlank())
            throw new IllegalStateException("Proposal message is incomplete");
        var draft = store.load(operationId);
        if (draft.status() != ScheduleImportStore.Status.PREVIEW_READY || draft.revision() != revision)
            throw new ConcurrentModificationException("Preview is no longer current");
        var current = pending(conversationId);
        if (current.isPresent() && current.get().messageId().equals(proposalMessageId)
                && current.get().operationId().equals(operationId) && current.get().revision() == revision) return;
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from aitls_ai_chat_message_entity
                where conversation_id=? and created_date>=? and id<>?)
                """, Boolean.class, conversationId, assistant.getCreatedDate(), assistant.getId())))
            throw new ConcurrentModificationException("Conversation has moved on");
        retire(conversationId);
        jdbc.update("insert into ai_schedule_proposal(message_id, conversation_id, operation_id, revision, fingerprint, pending) values(?,?,?,?,?,true)",
                proposalMessageId, conversationId, operationId, revision, draft.content().fingerprint());
    }

    /** Capture once, including absence; retries cannot substitute a newer observation. */
    public void captureObservation(UUID conversationId, UUID userMessageId, UUID observedProposalId, Long observedRevision) {
        lockConversation(conversationId);
        message(conversationId, userMessageId, USER);
        insertObservation(userMessageId, observedProposalId, observedRevision);
    }

    private void insertObservation(UUID userMessageId, UUID observedProposalId, Long observedRevision) {
        jdbc.update("insert into ai_schedule_reply(message_id,proposal_id,revision) values(?,?,?) on conflict(message_id) do nothing",
                userMessageId, observedProposalId, observedRevision);
    }

    public Optional<Proposal> pendingProposal(UUID conversationId) {
        lockConversation(conversationId);
        return pending(conversationId).filter(this::matchesDraft);
    }

    public ReplyOutcome handleReply(UUID conversationId, UUID userMessageId) {
        lockConversation(conversationId);
        var user = message(conversationId, userMessageId, USER);
        insertObservation(userMessageId, null, null);
        var observation = jdbc.queryForObject("select proposal_id, revision, outcome from ai_schedule_reply where message_id=?",
                (rs, n) -> new Observation(rs.getObject(1, UUID.class), rs.getObject(2, Long.class), rs.getString(3)), userMessageId);
        if (observation.outcome() != null) {
            var recorded = Outcome.valueOf(observation.outcome());
            if (recorded != Outcome.APPLIED) return new ReplyOutcome(recorded);
            UUID operation = jdbc.queryForObject("select operation_id from ai_schedule_proposal where message_id=? and conversation_id=?",
                    UUID.class, observation.proposalId(), conversationId);
            return new ReplyOutcome(Outcome.APPLIED, commits.confirm(operation, observation.revision()));
        }
        ReplyOutcome result = pending(conversationId)
                .map(proposal -> process(conversationId, user, observation, proposal))
                .orElseGet(() -> new ReplyOutcome(observation.proposalId() == null ? Outcome.NONE : Outcome.STALE));
        jdbc.update("update ai_schedule_reply set outcome=? where message_id=?", result.outcome().name(), userMessageId);
        return result;
    }

    private ReplyOutcome process(UUID conversationId, AiChatMessageEntity user, Observation observation, Proposal proposal) {
        var assistant = message(conversationId, proposal.messageId(), ASSISTANT);
        if (!Objects.requireNonNull(assistant.getCreatedDate()).isBefore(user.getCreatedDate())) return new ReplyOutcome(Outcome.STALE);
        if (!Objects.equals(observation.proposalId(), proposal.messageId())
                || !Objects.equals(observation.revision(), proposal.revision()) || !matchesDraft(proposal)
                || laterUserExists(conversationId, user) || interveningMessage(conversationId, assistant, user)) {
            retire(conversationId);
            return new ReplyOutcome(Outcome.STALE);
        }
        var intent = ScheduleConfirmationReplyClassifier.classify(user.getContent());
        if (intent == ScheduleConfirmationReplyClassifier.Intent.ACCEPT) return apply(proposal);
        retire(conversationId);
        if (intent == ScheduleConfirmationReplyClassifier.Intent.DECLINE) {
            store.cancel(proposal.operationId(), proposal.revision());
            return new ReplyOutcome(Outcome.DECLINED);
        }
        return new ReplyOutcome(Outcome.NEEDS_DIALOGUE);
    }

    private ReplyOutcome apply(Proposal proposal) {
        // Consume the published consent before attempting the domain write. A rollback or
        // process crash must require a fresh proposal. The outer conversation lock orders
        // publication and competing replies throughout both transactions. A successful
        // domain write and its reply outcome commit together in the outer transaction.
        Integer consumed = reserveConsent.execute(tx -> jdbc.update(
                "update ai_schedule_proposal set pending=false where message_id=? and pending", proposal.messageId()));
        if (!Integer.valueOf(1).equals(consumed)) return new ReplyOutcome(Outcome.STALE);
        return new ReplyOutcome(Outcome.APPLIED, commits.confirm(proposal.operationId(), proposal.revision()));
    }

    private boolean matchesDraft(Proposal p) {
        var draft = store.load(p.operationId());
        return draft.status() == ScheduleImportStore.Status.PREVIEW_READY && draft.revision() == p.revision()
                && p.fingerprint().equals(draft.content().fingerprint());
    }

    private void lockConversation(UUID conversationId) {
        ownership.conversation(conversationId);
        jdbc.queryForObject("select id from aitls_ai_conversation_entity where id=? and username=? for update",
                UUID.class, conversationId, ownership.username());
    }

    private AiChatMessageEntity message(UUID conversationId, UUID id, AiChatMessageEntityType type) {
        var m = data.load(AiChatMessageEntity.class).id(id).optional()
                .orElseThrow(() -> new SecurityException("Message is unavailable"));
        if (!conversationId.equals(m.getConversation().getId()) || m.getType() != type
                || m.getCreatedDate() == null || m.getCreatedDate().isAfter(OffsetDateTime.now())
                || (type == USER && !ownership.username().equals(m.getCreatedBy())))
            throw new SecurityException("Message is unavailable");
        return m;
    }

    private Optional<Proposal> pending(UUID conversationId) {
        return jdbc.query("select message_id, operation_id, revision, fingerprint from ai_schedule_proposal where conversation_id=? and pending",
                (rs, n) -> new Proposal(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4)),
                conversationId).stream().findFirst();
    }

    private boolean laterUserExists(UUID conversationId, AiChatMessageEntity message) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from aitls_ai_chat_message_entity
                where conversation_id=? and type_=? and created_date>=? and id<>?)
                """, Boolean.class, conversationId, USER.getId(), message.getCreatedDate(), message.getId()));
    }

    private boolean interveningMessage(UUID conversationId, AiChatMessageEntity proposal, AiChatMessageEntity user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from aitls_ai_chat_message_entity where conversation_id=?
                and created_date between ? and ? and id not in (?,?))
                """, Boolean.class, conversationId, proposal.getCreatedDate(), user.getCreatedDate(), proposal.getId(), user.getId()));
    }

    private void retire(UUID conversationId) {
        jdbc.update("update ai_schedule_proposal set pending=false where conversation_id=? and pending", conversationId);
    }
}
