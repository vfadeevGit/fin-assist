package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntity;
import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntityType;
import io.jmix.core.UnconstrainedDataManager;
import jakarta.persistence.LockModeType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.stnovator.finassist.entity.AiScheduleImport;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
    private record Observation(UUID proposalId, Long revision, String outcome, UUID appliedOperation, boolean contentMatches) {}
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
        var user = message(conversationId, userMessageId, USER);
        insertObservation(user, observedProposalId, observedRevision);
    }

    private void insertObservation(AiChatMessageEntity user, UUID observedProposalId, Long observedRevision) {
        jdbc.update("""
                insert into ai_schedule_reply(message_id,proposal_id,revision,submitted_digest)
                values(?,?,?,sha256(convert_to(?, 'UTF8'))) on conflict(message_id) do nothing
                """, user.getId(), observedProposalId, observedRevision, user.getContent());
    }

    public Optional<Proposal> pendingProposal(UUID conversationId) {
        lockConversation(conversationId);
        return pending(conversationId).filter(this::matchesDraft);
    }

    public ReplyOutcome handleReply(UUID conversationId, UUID userMessageId) {
        lockConversation(conversationId);
        var user = message(conversationId, userMessageId, USER);
        insertObservation(user, null, null);
        var observation = jdbc.queryForObject("""
                select proposal_id, revision, outcome, applied_operation_id, submitted_digest=sha256(convert_to(?, 'UTF8'))
                from ai_schedule_reply where message_id=?
                """, (rs, n) -> new Observation(rs.getObject(1, UUID.class), rs.getObject(2, Long.class), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getBoolean(5)), user.getContent(), userMessageId);
        if (observation.outcome() != null) {
            var recorded = Outcome.valueOf(observation.outcome());
            if (recorded != Outcome.APPLIED) return new ReplyOutcome(recorded);
            if (observation.appliedOperation() == null) return new ReplyOutcome(Outcome.STALE);
            var receipt = store.load(observation.appliedOperation());
            if (!receipt.conversationId().equals(conversationId) || receipt.status() != ScheduleImportStore.Status.SAVED)
                throw new SecurityException("Confirmation receipt is unavailable");
            return new ReplyOutcome(Outcome.APPLIED, commits.confirm(receipt.operationId(), observation.revision()));
        }
        ReplyOutcome result = pending(conversationId)
                .map(proposal -> process(conversationId, user, observation, proposal))
                .orElseGet(() -> new ReplyOutcome(observation.proposalId() == null ? Outcome.NONE : Outcome.STALE));
        recordOutcome(userMessageId, result);
        return result;
    }

    private void recordOutcome(UUID userId, ReplyOutcome result) {
        jdbc.update("update ai_schedule_reply set outcome=?, applied_operation_id=? where message_id=?", result.outcome().name(),
                result.result() == null ? null : result.result().operationId(), userId);
    }

    private ReplyOutcome process(UUID conversationId, AiChatMessageEntity user, Observation observation, Proposal proposal) {
        var assistant = message(conversationId, proposal.messageId(), ASSISTANT);
        if (!Objects.requireNonNull(assistant.getCreatedDate()).isBefore(user.getCreatedDate())) return new ReplyOutcome(Outcome.STALE);
        if (!observation.contentMatches() || !Objects.equals(observation.proposalId(), proposal.messageId())
                || !Objects.equals(observation.revision(), proposal.revision()) || !matchesDraft(proposal)) {
            retire(conversationId);
            return new ReplyOutcome(Outcome.STALE);
        }
        var intent = ScheduleConfirmationReplyClassifier.classify(user.getContent());
        var duplicates = duplicateAffirmatives(conversationId, assistant, user, proposal.revision());
        boolean accepted = intent == ScheduleConfirmationReplyClassifier.Intent.ACCEPT;
        if (duplicates.isEmpty() || (!accepted && !duplicates.get().isEmpty())) {
            retire(conversationId);
            return new ReplyOutcome(Outcome.STALE);
        }
        if (accepted) {
            var result = apply(proposal);
            duplicates.get().forEach(id -> recordOutcome(id, result));
            return result;
        }
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
        var m = data.load(AiChatMessageEntity.class).id(id).lockMode(LockModeType.PESSIMISTIC_READ).optional()
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

    /** A discussion breaks the link. Independently captured, identical-in-intent consents may share a receipt. */
    private Optional<List<UUID>> duplicateAffirmatives(UUID conversationId, AiChatMessageEntity assistant,
            AiChatMessageEntity user, long revision) {
        return jdbc.query("""
                select m.id, m.content,
                       (m.type_=? and m.created_by=? and m.created_date>? and m.created_date<=clock_timestamp()
                        and r.proposal_id=? and r.revision=? and r.outcome is null
                        and r.submitted_digest=sha256(convert_to(m.content,'UTF8'))) as eligible
                from aitls_ai_chat_message_entity m left join ai_schedule_reply r on r.message_id=m.id
                where m.conversation_id=? and m.id not in (?,?) and m.created_date>=?
                  and (m.created_date<=? or m.type_=?)
                for share of m
                """, rs -> {
                    List<UUID> duplicates = new ArrayList<>();
                    while (rs.next()) {
                        if (!rs.getBoolean("eligible") || ScheduleConfirmationReplyClassifier.classify(rs.getString("content"))
                                != ScheduleConfirmationReplyClassifier.Intent.ACCEPT) return Optional.empty();
                        duplicates.add(rs.getObject("id", UUID.class));
                    }
                    return Optional.of(duplicates);
                }, USER.getId(), ownership.username(), assistant.getCreatedDate(), assistant.getId(), revision,
                conversationId, assistant.getId(), user.getId(), assistant.getCreatedDate(), user.getCreatedDate(), USER.getId());
    }

    private void retire(UUID conversationId) {
        jdbc.update("update ai_schedule_proposal set pending=false where conversation_id=? and pending", conversationId);
    }
}
