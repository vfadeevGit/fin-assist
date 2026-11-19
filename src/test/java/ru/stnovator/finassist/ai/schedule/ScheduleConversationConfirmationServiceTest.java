package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitoolsflowuidata.entity.*;
import io.jmix.core.UnconstrainedDataManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static io.jmix.aitoolsflowuidata.entity.AiChatMessageEntityType.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleConversationConfirmationService.Outcome.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

@SpringBootTest
@org.springframework.context.annotation.Import(ScheduleImportCommitServiceTest.FailureConfiguration.class)
class ScheduleConversationConfirmationServiceTest {
    @Autowired ScheduleConversationConfirmationService service;
    @Autowired UnconstrainedDataManager data;
    @Autowired org.springframework.context.ApplicationContext context;
    final ScheduleImportCommitServiceTest fixtures = new ScheduleImportCommitServiceTest();

    @BeforeEach void setup() {
        context.getAutowireCapableBeanFactory().autowireBean(fixtures);
        fixtures.setup();
    }
    @AfterEach void cleanup() { fixtures.cleanup(); }

    UUID message(AiChatMessageEntityType type, String text) {
        var m = data.create(AiChatMessageEntity.class);
        m.setConversation(data.load(AiConversationEntity.class).id(fixtures.conversation).one());
        m.setContent(text);
        m.setType(type);
        return data.save(m).getId();
    }
    UUID publish(ScheduleImportPreview preview) {
        UUID proposal = message(ASSISTANT, "Применить эти изменения?");
        service.publishProposal(preview.operationId(), preview.revision(), proposal);
        return proposal;
    }
    UUID reply(UUID proposal, long revision, String text) {
        UUID user = message(USER, text);
        service.captureObservation(fixtures.conversation, user, proposal, revision);
        return user;
    }
    @Test void publicationRecognizesOnlyItsExactBoundContent() {
        var preview = fixtures.preview(Kind.PAYMENT, Mode.FILL);
        String full = "2026-06-01 | 120.00\n2026-06-02 | 80.00\nПрименить эти изменения?";
        UUID id = message(ASSISTANT, full);
        service.publishProposal(preview.operationId(), preview.revision(), id);
        var publication = service.pendingProposal(fixtures.conversation).orElseThrow();
        assertThat(publication.matchesDisplayed(id, full)).isTrue();
        assertThat(publication.matchesDisplayed(id, "")).isFalse();
        assertThat(publication.matchesDisplayed(id, full.replace("80.00", "81.00"))).isFalse();
        assertThat(publication.matchesDisplayed(UUID.randomUUID(), full)).isFalse();
        var assistant = data.load(AiChatMessageEntity.class).id(id).one();
        assistant.setContent("Новая редакция");
        data.saveWithoutReload(assistant);
        assertThat(service.pendingProposal(fixtures.conversation)).isEmpty();
    }

    @Test void onlyActualObservedProposalCanCommitAndRetry() {
        var preview = fixtures.preview(Kind.PAYMENT, Mode.FILL);
        assertThat(service.pendingProposal(fixtures.conversation)).isEmpty();
        UUID proposal = publish(preview);
        UUID user = reply(proposal, preview.revision(), "Да");
        var first = service.handleReply(fixtures.conversation, user);
        assertThat(first.outcome()).isEqualTo(APPLIED);
        assertThat(service.handleReply(fixtures.conversation, user).result().entityId()).isEqualTo(first.result().entityId());
        fixtures.assertRows(fixtures.items(Kind.PAYMENT, first.result().entityId(), false));
    }
    @Test void supersededAndUnobservedCannotApply() {
        var p = fixtures.preview(Kind.PAYMENT, Mode.FILL);
        UUID v1 = publish(p);
        publish(p);
        UUID stale = reply(v1, p.revision(), "да");
        assertThat(service.handleReply(fixtures.conversation, stale).outcome()).isEqualTo(STALE);
        publish(p);
        UUID missing = message(USER, "да");
        assertThat(service.handleReply(fixtures.conversation, missing).outcome()).isEqualTo(STALE);
        fixtures.assertPending(p);
    }
    @Test void declineCancelsWithoutSaving() {
        var p = fixtures.preview(Kind.PAYMENT, Mode.FILL);
        UUID user = reply(publish(p), p.revision(), "Нет");
        assertThat(service.handleReply(fixtures.conversation, user).outcome()).isEqualTo(DECLINED);
        assertThat(fixtures.store.load(p.operationId()).status()).isEqualTo(ScheduleImportStore.Status.CANCELLED);
    }

    @Test void discussionInvalidatesAndRequiresAnotherPublication() {
        var p = fixtures.preview(Kind.PAYMENT, Mode.FILL);
        UUID proposal = publish(p);
        UUID user = reply(proposal, p.revision(), "Да, но измени сумму");
        assertThat(service.handleReply(fixtures.conversation, user).outcome()).isEqualTo(NEEDS_DIALOGUE);
        assertThat(service.pendingProposal(fixtures.conversation)).isEmpty();
        assertThat(service.handleReply(fixtures.conversation, reply(proposal, p.revision(), "да")).outcome()).isEqualTo(STALE);
        fixtures.assertPending(p);
    }

    @Test void changedDraftRevisionAndIncompleteMessagesCannotConfirm() {
        var p = fixtures.preview(Kind.PAYMENT, Mode.FILL);
        UUID empty = message(ASSISTANT, "");
        assertThatThrownBy(() -> service.publishProposal(p.operationId(), p.revision(), empty))
                .isInstanceOf(IllegalStateException.class);
        UUID user = reply(publish(p), p.revision(), "Да");
        var draft = fixtures.store.load(p.operationId());
        fixtures.store.update(p.operationId(), draft.revision(), draft.content());
        assertThat(service.handleReply(fixtures.conversation, user).outcome()).isEqualTo(STALE);
        assertThat(fixtures.store.load(p.operationId()).resultId()).isNull();
    }

    @Test void futureDatedUserIsRejected() {
        var p = fixtures.preview(Kind.PAYMENT, Mode.FILL);
        UUID user = reply(publish(p), p.revision(), "Да");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(context.getBean(javax.sql.DataSource.class));
        jdbc.update("update aitls_ai_chat_message_entity set created_date=current_timestamp + interval '1 day' where id=?", user);
        assertThatThrownBy(() -> service.handleReply(fixtures.conversation, user)).isInstanceOf(SecurityException.class);
        fixtures.assertPending(p);
    }

    @Test void realUserOwnerAndMessageOrderingAreRequired() {
        var p = fixtures.preview(Kind.PAYMENT, Mode.FILL);
        UUID before = message(USER, "да");
        UUID proposal = publish(p);
        service.captureObservation(fixtures.conversation, before, proposal, p.revision());
        assertThat(service.handleReply(fixtures.conversation, before).outcome()).isEqualTo(STALE);
        assertThatThrownBy(() -> service.handleReply(fixtures.conversation, proposal)).isInstanceOf(SecurityException.class);
        fixtures.login("foreign", "ROLE_finance-manager-role");
        assertThatThrownBy(() -> service.handleReply(fixtures.conversation, before)).isInstanceOf(SecurityException.class);
        fixtures.login();
        fixtures.assertPending(p);
    }

    @Test void changedHistoryAndRevokedPermissionsRequireFreshConsent() {
        var old = fixtures.fixture.payment(fixtures.date, "100");
        var p = fixtures.preview(Kind.PAYMENT, Mode.REPLACE);
        UUID changed = reply(publish(p), p.revision(), "да");
        old.setAmount(new java.math.BigDecimal("101"));
        fixtures.data.saveWithoutReload(old);
        assertThatThrownBy(() -> service.handleReply(fixtures.conversation, changed))
                .isInstanceOf(java.util.ConcurrentModificationException.class);
        assertThat(service.pendingProposal(fixtures.conversation)).isEmpty();
        var fresh = fixtures.preview(Kind.PAYMENT, Mode.REPLACE);
        UUID denied = reply(publish(fresh), fresh.revision(), "да");
        fixtures.login(fixtures.owner, "ROLE_commit-readonly-amount");
        assertThatThrownBy(() -> service.handleReply(fixtures.conversation, denied))
                .isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
        fixtures.login();
        assertThat(service.handleReply(fixtures.conversation, denied).outcome()).isEqualTo(STALE);
        fixtures.assertPending(fresh);
    }

    @Test void lateFailureRollsBackDomainAndRetryCreatesOnlyOneReceipt() throws Exception {
        var p = fixtures.preview(Kind.PAYMENT, Mode.FILL);
        UUID user = reply(publish(p), p.revision(), "да");
        fixtures.failure.project = fixtures.fixture.project.getId();
        fixtures.failure.lastDate = fixtures.date.plusDays(1);
        assertThatThrownBy(() -> service.handleReply(fixtures.conversation, user)).hasStackTraceContaining("intentional late item failure");
        fixtures.failure.project = null;
        assertThat(fixtures.items(Kind.PAYMENT, fixtures.fixture.payment.getId(), false)).isEmpty();
        fixtures.assertPending(p);
        assertThat(service.handleReply(fixtures.conversation, user).outcome()).isEqualTo(STALE);
        UUID freshConsent = reply(publish(p), p.revision(), "Да");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<UUID> call = () -> {
                fixtures.login();
                try {
                    start.await();
                    return service.handleReply(fixtures.conversation, freshConsent).result().entityId();
                } finally {
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                }
            };
            var a = pool.submit(call);
            var b = pool.submit(call);
            start.countDown();
            assertThat(a.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(20, java.util.concurrent.TimeUnit.SECONDS));
        }
        fixtures.assertRows(fixtures.items(Kind.PAYMENT, fixtures.fixture.payment.getId(), false));
    }

    @Test void distinctCapturedAffirmativesCommittedOnce() throws Exception {
        var p = fixtures.preview(Kind.PAYMENT, Mode.FILL);
        UUID proposal = publish(p);
        UUID a = reply(proposal, p.revision(), "Да");
        UUID b = reply(proposal, p.revision(), "Да, применить");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.function.Function<UUID, java.util.concurrent.Callable<UUID>> handler = id -> () -> {
                fixtures.login();
                try {
                    start.await();
                    return service.handleReply(fixtures.conversation, id).result().entityId();
                } finally {
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                }
            };
            var first = pool.submit(handler.apply(a));
            var second = pool.submit(handler.apply(b));
            start.countDown();
            assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(20, java.util.concurrent.TimeUnit.SECONDS));
        }
        fixtures.assertRows(fixtures.items(Kind.PAYMENT, fixtures.fixture.payment.getId(), false));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void assistantQuestionBetweenDistinctAffirmativesBreaksConsent(boolean earlierFirst) {
        var p = fixtures.preview(Kind.PAYMENT, Mode.FILL);
        UUID proposal = publish(p);
        UUID first = reply(proposal, p.revision(), "Да");
        message(ASSISTANT, "Вы имели в виду первый лист?");
        UUID last = reply(proposal, p.revision(), "Да");
        assertThat(service.handleReply(fixtures.conversation, earlierFirst ? first : last).outcome()).isEqualTo(STALE);
        assertThat(service.handleReply(fixtures.conversation, earlierFirst ? last : first).outcome()).isEqualTo(STALE);
        fixtures.assertPending(p);
        assertThat(fixtures.items(Kind.PAYMENT, fixtures.fixture.payment.getId(), false)).isEmpty();
    }

    @Test void discussionBetweenTwoAffirmativesRequiresFreshProposal() {
        var p = fixtures.preview(Kind.PAYMENT, Mode.FILL);
        UUID proposal = publish(p);
        UUID first = reply(proposal, p.revision(), "Да");
        reply(proposal, p.revision(), "А что изменится?");
        UUID last = reply(proposal, p.revision(), "Да");
        assertThat(service.handleReply(fixtures.conversation, last).outcome()).isEqualTo(STALE);
        assertThat(service.handleReply(fixtures.conversation, first).outcome()).isEqualTo(STALE);
        fixtures.assertPending(p);
    }

    @Test void mutatedRefusalCannotConfirm() {
        var p = fixtures.preview(Kind.PAYMENT, Mode.FILL);
        UUID user = reply(publish(p), p.revision(), "Нет");
        var message = data.load(AiChatMessageEntity.class).id(user).one();
        message.setContent("Да");
        data.saveWithoutReload(message);
        assertThat(service.handleReply(fixtures.conversation, user).outcome()).isEqualTo(STALE);
        fixtures.assertPending(p);
    }

    @Test void retainedUserReceiptSurvivesAssistantDeletion() {
        var p = fixtures.preview(Kind.PAYMENT, Mode.FILL);
        UUID proposal = publish(p);
        UUID user = reply(proposal, p.revision(), "Да");
        var result = service.handleReply(fixtures.conversation, user).result();
        data.remove(data.load(AiChatMessageEntity.class).id(proposal).one());
        assertThat(service.handleReply(fixtures.conversation, user).result().entityId()).isEqualTo(result.entityId());
        var other = data.create(AiConversationEntity.class);
        other.setUsername(fixtures.owner);
        other.setTitle("Other conversation");
        var saved = data.save(other);
        try {
            var moved = data.load(AiChatMessageEntity.class).id(user).one();
            moved.setConversation(saved);
            data.saveWithoutReload(moved);
            assertThatThrownBy(() -> service.handleReply(saved.getId(), user)).isInstanceOf(SecurityException.class);
        } finally {
            data.remove(saved);
        }
    }
}
