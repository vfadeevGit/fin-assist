package ru.stnovator.finassist.ai.schedule;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import ru.stnovator.finassist.entity.*;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

@SpringBootTest
class ExistingAddendumScheduleTest {
    @Autowired ApplicationContext context;
    final ScheduleImportAcceptanceTest domain = new ScheduleImportAcceptanceTest();
    final LocalDate effectiveDate = LocalDate.parse("2026-11-09");
    final LocalDate shipmentDate = LocalDate.parse("2026-12-01");

    @BeforeEach void setup() {
        context.getAutowireCapableBeanFactory().autowireBean(domain);
        domain.setup();
    }
    @AfterEach void cleanup() { domain.cleanup(); }

    Addendum addendum(String number, LocalDate date) {
        var a = domain.data.create(Addendum.class);
        a.setContract(domain.fixture.contract); a.setNumber(number); a.setEffectiveDate(date);
        return domain.fixture.save(a);
    }
    ScheduleImportStore.Draft draft(Kind kind) {
        var d = domain.workflow.start(domain.conversation, kind);
        d = domain.workflow.setRows(d.operationId(), d.revision(),
                List.of(new InputRow(shipmentDate.toString(), "100000", "test")), null);
        domain.workflow.selectContract(d.operationId(), d.revision(), domain.fixture.contract.getId());
        return domain.store.load(d.operationId());
    }
    ScheduleImportPreview preview(ScheduleImportStore.Draft d) {
        return domain.workflow.preview(d.operationId(), d.revision(), Mode.ADDENDUM, null, null);
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void existingDocumentGetsSnapshotWithoutChangingBaseOrDocument(Kind kind) {
        var a = addendum("4", effectiveDate);
        var before = domain.effective.load(domain.fixture.project.getId(), kind, null);
        var d = draft(kind);
        d = domain.workflow.selectExistingAddendum(d.operationId(), d.revision(), a.getId());
        var p = preview(d);
        assertThat(p.number()).isEqualTo("4");
        assertThat(p.effectiveDate()).isEqualTo(effectiveDate);
        assertThat(p.comparison().beforeTotal()).isEqualByComparingTo(
                before.rows().stream().map(Row::amount).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        var result = domain.commits.confirm(p.operationId(), p.revision());
        assertThat(result.entityId()).isEqualTo(a.getId());
        assertThat(domain.commits.confirm(p.operationId(), p.revision()).alreadySaved()).isTrue();
        var saved = domain.data.load(Addendum.class).id(a.getId()).one();
        assertThat(saved.getVersion()).isEqualTo(a.getVersion());
        assertThat(domain.data.load(Addendum.class).query("e.contract.id=:c")
                .parameter("c", domain.fixture.contract.getId()).list()).hasSize(1);
        assertThat(domain.effective.load(domain.fixture.project.getId(), kind, effectiveDate.minusDays(1)).rows())
                .isEqualTo(before.rows());
        assertThat(domain.effective.load(domain.fixture.project.getId(), kind, effectiveDate).rows()).isEqualTo(p.rows());
        String entity = kind == Kind.PAYMENT ? "PaymentScheduleCorrection" : "ShipmentScheduleCorrection";
        assertThat(domain.internal.load(domain.metadata.getClass(entity).getJavaClass())
                .query("e.addendum.id=:a and e.project.id=:p").parameter("a", a.getId())
                .parameter("p", domain.fixture.project.getId()).list()).hasSize(1);
    }

    @Test void absentBaseStillAllowsExistingAddendum() {
        domain.data.remove(domain.fixture.payment);
        var a = addendum("4", effectiveDate); var d = draft(Kind.PAYMENT);
        d = domain.workflow.selectExistingAddendum(d.operationId(), d.revision(), a.getId());
        assertThat(domain.workflow.allowedModes(d.operationId())).containsExactly(Mode.ADDENDUM);
        var p = preview(d); domain.commits.confirm(p.operationId(), p.revision());
        assertThat(domain.effective.load(domain.fixture.project.getId(), Kind.PAYMENT, null).baseScheduleId()).isNull();
    }

    @Test void rowsAndContractEditsRetainIntentAndPreventFallback() {
        var a = addendum("4", effectiveDate); var d = draft(Kind.PAYMENT);
        d = domain.workflow.selectExistingAddendum(d.operationId(), d.revision(), a.getId());
        d = domain.workflow.setRows(d.operationId(), d.revision(), List.of(new InputRow("2026-12-02", "123", "edit")), null);
        assertThat(d.content().existingAddendumId()).isEqualTo(a.getId());
        var edited = d;
        assertThatThrownBy(() -> domain.workflow.preview(edited.operationId(), edited.revision(), Mode.FILL, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(domain.store.load(d.operationId()).content().existingAddendumRequired()).isTrue();
    }

    @Test void emptyExistingCorrectionAndLaterHistoryAreRejected() {
        var a = addendum("4", effectiveDate);
        var correction = domain.data.create(ShipmentScheduleCorrection.class);
        correction.setProject(domain.fixture.project); correction.setAddendum(a); domain.fixture.save(correction);
        var d = draft(Kind.SHIPMENT);
        var selected = domain.workflow.selectExistingAddendum(d.operationId(), d.revision(), a.getId());
        assertThatThrownBy(() -> preview(selected)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("корректировка");
        var earlier = addendum("3", effectiveDate.minusDays(1));
        var other = domain.workflow.selectExistingAddendum(selected.operationId(), selected.revision(), earlier.getId());
        assertThatThrownBy(() -> preview(other)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("поздн");
    }

    @Test void changedDocumentInvalidatesConfirmation() {
        var a = addendum("4", effectiveDate); var d = draft(Kind.SHIPMENT);
        d = domain.workflow.selectExistingAddendum(d.operationId(), d.revision(), a.getId());
        var p = preview(d); var before = domain.counts();
        a.setNumber("4-renamed"); domain.data.saveWithoutReload(a);
        assertThatThrownBy(() -> domain.commits.confirm(p.operationId(), p.revision()))
                .isInstanceOf(ConcurrentModificationException.class);
        assertThat(domain.counts()).isEqualTo(before);
    }
    @Test void changedContractClearsDocumentButKeepsCorrectionIntent() {
        var a = addendum("4", effectiveDate); var d = draft(Kind.PAYMENT);
        d = domain.workflow.selectExistingAddendum(d.operationId(), d.revision(), a.getId());
        try (var other = new ru.stnovator.finassist.test_support.RelatedFixtures(domain.data, domain.auth)) {
            domain.workflow.selectContract(d.operationId(), d.revision(), other.contract.getId());
            var changed = domain.store.load(d.operationId());
            assertThat(changed.content().existingAddendumId()).isNull();
            assertThat(changed.content().existingAddendumRequired()).isTrue();
            assertThat(domain.workflow.allowedModes(changed.operationId())).containsExactly(Mode.ADDENDUM);
            assertThatThrownBy(() -> preview(changed)).hasMessageContaining("Выберите существующее");
            assertThatThrownBy(() -> domain.workflow.selectExistingAddendum(changed.operationId(), changed.revision(), a.getId()))
                    .isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
        }
    }

    @Test void oppositeKindAndOtherProjectCorrectionsDoNotBlock() {
        var a = addendum("4", effectiveDate);
        var payment = domain.data.create(PaymentScheduleCorrection.class);
        payment.setAddendum(a); payment.setProject(domain.fixture.project); domain.fixture.save(payment);
        var second = domain.data.create(Project.class);
        second.setName("other"); second.setContract(domain.fixture.contract);
        second.setLineOfBusiness(domain.fixture.project.getLineOfBusiness()); second = domain.fixture.save(second);
        var shipment = domain.data.create(ShipmentScheduleCorrection.class);
        shipment.setAddendum(a); shipment.setProject(second); domain.fixture.save(shipment);
        var d = draft(Kind.SHIPMENT);
        d = domain.workflow.selectProject(d.operationId(), d.revision(), domain.fixture.project.getId());
        d = domain.workflow.selectExistingAddendum(d.operationId(), d.revision(), a.getId());
        var p = preview(d);
        assertThat(domain.commits.confirm(p.operationId(), p.revision()).entityId()).isEqualTo(a.getId());
    }

    @Test void hiddenDocumentAndRevokedCorrectionWriteRightsDenyWithoutWrites() {
        var a = addendum("hidden-workflow", effectiveDate);
        var d = draft(Kind.PAYMENT);
        var selected = domain.workflow.selectExistingAddendum(d.operationId(), d.revision(), a.getId());
        var p = preview(selected); var before = domain.counts();
        var user = domain.internal.create(User.class); user.setUsername(domain.internal.load(io.jmix.aitoolsflowuidata.entity.AiConversationEntity.class).id(domain.conversation).one().getUsername());
        ru.stnovator.finassist.test_support.DashboardFixtures.restricted(user, "workflow-hidden-addendum");
        assertThat(domain.tool.conversationState(domain.conversation).getFirst().existingAddendumId()).isNull();
        assertThatThrownBy(() -> domain.workflow.findAddenda(d.operationId()))
                .isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
        assertThatThrownBy(() -> domain.commits.confirm(p.operationId(), p.revision()))
                .isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
        ru.stnovator.finassist.test_support.DashboardFixtures.restricted(user, "workflow-correction-total-cap");
        assertThatThrownBy(() -> domain.commits.confirm(p.operationId(), p.revision()))
                .isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
        assertThat(domain.counts()).isEqualTo(before);
    }

    @Test void addedCorrectionBetweenPreviewAndCommitIsRejectedAndRecoveryKeepsIntent() {
        var a = addendum("4", effectiveDate); var d = draft(Kind.SHIPMENT);
        d = domain.workflow.selectExistingAddendum(d.operationId(), d.revision(), a.getId());
        var p = preview(d);
        var later = addendum("5", effectiveDate.plusDays(1));
        var correction = domain.data.create(ShipmentScheduleCorrection.class);
        correction.setAddendum(later); correction.setProject(domain.fixture.project); domain.fixture.save(correction);
        var before = domain.counts();
        assertThatThrownBy(() -> domain.commits.confirm(p.operationId(), p.revision())).hasMessageContaining("поздней");
        var recovery = domain.workflow.refresh(p.operationId(), p.revision());
        assertThat(recovery.preview()).isNull();
        assertThat(recovery.correctionPrompt()).isEqualTo("existingLaterCorrection");
        var restored = domain.store.load(p.operationId());
        assertThat(restored.content().existingAddendumId()).isEqualTo(a.getId());
        assertThat(restored.content().existingAddendumRequired()).isTrue();
        assertThat(domain.counts()).isEqualTo(before);
    }

    @Test void oldJsonWithoutSelectionRemainsReadable() {
        var d = draft(Kind.PAYMENT);
        var entity = domain.internal.load(AiScheduleImport.class).id(d.operationId()).one();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var envelope = (tools.jackson.databind.node.ObjectNode) json.readTree(entity.getContentJson());
        var content = (tools.jackson.databind.node.ObjectNode) envelope.get("content");
        content.remove("existingAddendumRequired"); content.remove("existingAddendumId");
        entity.setContentJson(json.writeValueAsString(envelope)); domain.internal.saveWithoutReload(entity);
        var old = domain.store.load(d.operationId());
        assertThat(old.content().existingAddendumRequired()).isFalse();
        assertThat(old.content().existingAddendumId()).isNull();
        assertThat(domain.workflow.allowedModes(d.operationId())).containsExactly(Mode.FILL);
    }

    @Test void storedDocumentNumberIsPreservedExactly() {
        var a = addendum(" 4 ", effectiveDate); var d = draft(Kind.SHIPMENT);
        d = domain.workflow.selectExistingAddendum(d.operationId(), d.revision(), a.getId());
        var p = preview(d);
        assertThat(p.number()).isEqualTo(" 4 ");
        assertThat(domain.commits.confirm(p.operationId(), p.revision()).entityId()).isEqualTo(a.getId());
        assertThat(domain.data.load(Addendum.class).id(a.getId()).one().getNumber()).isEqualTo(" 4 ");
    }

    @Test void comparisonUsesEarlierCorrectionRatherThanBase() {
        var earlier = addendum("3", effectiveDate.minusDays(1));
        var header = domain.data.create(ShipmentScheduleCorrection.class);
        header.setProject(domain.fixture.project); header.setAddendum(earlier); header = domain.fixture.save(header);
        var item = domain.data.create(ShipmentScheduleCorrectionItem.class);
        item.setCorrection(header); item.setItemDate(shipmentDate); item.setAmount(new java.math.BigDecimal("500")); domain.fixture.save(item);
        var a = addendum("4", effectiveDate); var d = draft(Kind.SHIPMENT);
        d = domain.workflow.selectExistingAddendum(d.operationId(), d.revision(), a.getId());
        assertThat(preview(d).comparison().beforeTotal()).isEqualByComparingTo("500");
    }

}
