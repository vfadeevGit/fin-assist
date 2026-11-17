package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitoolsflowuidata.entity.AiConversationEntity;
import io.jmix.core.*;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.test_support.DashboardFixtures;
import ru.stnovator.finassist.test_support.RelatedFixtures;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

@SpringBootTest
class ScheduleImportWorkflowTest {
    @Autowired ScheduleImportWorkflow workflow;
    @Autowired ScheduleImportStore store;
    @Autowired DataManager data;
    @Autowired UnconstrainedDataManager internal;
    @Autowired SystemAuthenticator auth;
    RelatedFixtures fixture;
    UUID conversation;
    final String owner = "workflow-" + UUID.randomUUID();
    final LocalDate date = LocalDate.of(2026, 6, 1);

    @BeforeEach void setup() {
        auth.begin("admin");
        fixture = new RelatedFixtures(data, auth);
        var c = internal.create(AiConversationEntity.class); c.setUsername(owner); c.setTitle("workflow");
        conversation = internal.save(c).getId();
        login();
    }
    void login() { var user = internal.create(User.class); user.setUsername(owner); DashboardFixtures.authenticate(user, "ROLE_finance-manager-role"); }
    @AfterEach void cleanup() {
        internal.load(AiScheduleImport.class).all().list().stream().filter(e -> owner.equals(e.getOwner())).forEach(internal::remove);
        internal.load(AiConversationEntity.class).id(conversation).optional().ifPresent(internal::remove);
        try { fixture.close(); } finally { auth.end(); }
    }
    ScheduleImportStore.Draft prepared() {
        var draft = workflow.start(conversation, Kind.PAYMENT);
        draft = workflow.setRows(draft.operationId(), draft.revision(), List.of(new InputRow(date.toString(), "120", "message:1")), null);
        workflow.selectContract(draft.operationId(), draft.revision(), fixture.contract.getId());
        return store.load(draft.operationId());
    }
    ScheduleImportPreview preview(ScheduleImportStore.Draft draft, Mode mode) {
        return workflow.preview(draft.operationId(), draft.revision(), mode, "new", date.plusYears(1));
    }
    @Test void contractSearchNormalizesBothSidesInDatabaseAndPreservesLiteralSubstring() {
        var contract=data.load(Contract.class).id(fixture.contract.getId()).one();
        contract.setInternalID("КАПСТРОЙ-ABC_%_"+UUID.randomUUID().toString().substring(0,8));data.saveWithoutReload(contract);
        var draft=workflow.start(conversation,Kind.PAYMENT);
        assertThat(workflow.findContracts(draft.operationId(),"  КАПСТРОЙ  "))
                .extracting(ScheduleImportWorkflow.Choice::id).containsExactly(contract.getId());
        assertThat(workflow.findContracts(draft.operationId(),"abc_%"))
                .extracting(ScheduleImportWorkflow.Choice::id).containsExactly(contract.getId());
        assertThat(workflow.findContracts(draft.operationId(),"ABC%"))
                .extracting(ScheduleImportWorkflow.Choice::id).doesNotContain(contract.getId());
    }
    @Test void selectsOnlyProjectAndAsksForSeveral() {
        var only = prepared();
        assertThat(only.content().projectId()).isEqualTo(fixture.project.getId());
        var second = data.create(Project.class); second.setName("second"); second.setContract(fixture.contract); second.setLineOfBusiness(fixture.project.getLineOfBusiness()); fixture.save(second);
        var choices = workflow.selectContract(only.operationId(), only.revision(), fixture.contract.getId());
        assertThat(choices).hasSize(2);
        assertThat(store.load(only.operationId()).content().projectId()).isNull();
        var current = store.load(only.operationId());
        assertThat(workflow.selectProject(current.operationId(), current.revision(), second.getId()).content().projectId()).isEqualTo(second.getId());
    }
    @Test void noProjectsRequestsAnotherContract() {
        data.remove(fixture.project);
        var draft = workflow.start(conversation, Kind.PAYMENT);
        assertThat(workflow.selectContract(draft.operationId(), draft.revision(), fixture.contract.getId())).isEmpty();
        assertThat(store.load(draft.operationId()).content().projectId()).isNull();
        assertThatThrownBy(() -> workflow.allowedModes(draft.operationId())).isInstanceOf(IllegalStateException.class);
    }
    @Test void missingAndEmptyScheduleModes() {
        var draft = prepared();
        assertThat(workflow.allowedModes(draft.operationId())).containsExactly(Mode.FILL);
        data.remove(fixture.payment);
        assertThat(workflow.allowedModes(draft.operationId())).containsExactly(Mode.CREATE);
    }
    @Test void correctionsDisallowReplacement() {
        fixture.payment(date, "100");
        var draft = prepared();
        assertThat(workflow.allowedModes(draft.operationId())).containsExactlyInAnyOrder(Mode.REPLACE, Mode.ADDENDUM);
        correction("future-empty", date.plusYears(3));
        assertThat(workflow.allowedModes(draft.operationId())).containsExactly(Mode.ADDENDUM);
        assertThatThrownBy(() -> preview(draft, Mode.REPLACE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preview(draft, Mode.ADDENDUM)).isInstanceOf(IllegalArgumentException.class);
        var result = workflow.preview(draft.operationId(), draft.revision(), Mode.ADDENDUM, "later", date.plusYears(4));
        assertThat(result.comparison().beforeTotal()).isEqualByComparingTo("100");
    }
    @Test void unchangedScheduleDoesNotPrepareSave() {
        fixture.payment(date, "120");
        var draft = prepared(); var result = preview(draft, Mode.REPLACE);
        assertThat(result.noChanges()).isTrue();
        assertThat(store.load(draft.operationId()).status()).isEqualTo(ScheduleImportStore.Status.COLLECTING);
        assertThatThrownBy(() -> workflow.getPreview(draft.operationId(), result.revision())).isInstanceOf(IllegalStateException.class);
    }
    @Test void requiresAddendumMetadataBeforePreview() {
        fixture.payment(date, "100"); var draft = prepared();
        for (String number : Arrays.asList(null, " ", "x".repeat(51))) {
            assertThatThrownBy(() -> workflow.preview(draft.operationId(), draft.revision(), Mode.ADDENDUM, number, date)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> workflow.preview(draft.operationId(), draft.revision(), Mode.ADDENDUM, "new", null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsBackdatedOrDuplicateDate() {
        fixture.payment(date, "100"); correction("existing", date.plusDays(1)); var draft = prepared();
        for (LocalDate when : List.of(date, date.plusDays(1))) {
            assertThatThrownBy(() -> workflow.preview(draft.operationId(), draft.revision(), Mode.ADDENDUM, "new", when)).isInstanceOf(IllegalArgumentException.class);
        }
        var addendum = data.create(Addendum.class); addendum.setContract(fixture.contract); addendum.setNumber("other-project"); addendum.setEffectiveDate(date.plusYears(1)); fixture.save(addendum);
        assertThatThrownBy(() -> workflow.preview(draft.operationId(), draft.revision(), Mode.ADDENDUM, "new", date.plusYears(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> workflow.preview(draft.operationId(), draft.revision(), Mode.ADDENDUM, "existing", date.plusYears(2))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void previewRestoresSameRevisionAndRejectsChangedHistoryWithoutAdvancing() {
        var item = fixture.payment(date, "100"); var draft = prepared();
        var before = domainCounts(); var result = preview(draft, Mode.REPLACE);
        assertThat(result.revision()).isGreaterThan(draft.revision());
        assertThat(result.contractCaption()).isEqualTo(fixture.contract.getInternalID());
        assertThat(result.projectCaption()).isEqualTo(fixture.project.getName());
        assertThat(result.total()).isEqualByComparingTo("120");
        assertThat(workflow.getPreview(draft.operationId(), result.revision())).isEqualTo(result);
        assertThat(domainCounts()).isEqualTo(before);
        item.setAmount(new java.math.BigDecimal("101")); data.saveWithoutReload(item);
        assertThatThrownBy(() -> workflow.getPreview(draft.operationId(), result.revision())).isInstanceOf(ConcurrentModificationException.class);
        assertThat(store.load(draft.operationId()).revision()).isEqualTo(result.revision());
    }
    @Test void expiredPreviewRestoreDoesNotAdvanceRevision() {
        var draft = prepared(); var result = preview(draft, Mode.FILL);
        var entity = internal.load(AiScheduleImport.class).id(draft.operationId()).one();
        entity.setTouchedAt(java.time.OffsetDateTime.now().minusHours(25)); internal.saveWithoutReload(entity);
        assertThatThrownBy(() -> workflow.getPreview(draft.operationId(), result.revision())).isInstanceOf(RuntimeException.class);
        assertThat(internal.load(AiScheduleImport.class).id(draft.operationId()).one().getRevision()).isEqualTo(result.revision());
    }
    @Test void restorationRechecksWriteRights() {
        var draft = prepared(); var result = preview(draft, Mode.FILL);
        restricted("workflow-deny-item-create");
        assertThatThrownBy(() -> workflow.getPreview(draft.operationId(), result.revision())).isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
        login();
        assertThat(store.load(draft.operationId()).revision()).isEqualTo(result.revision());
    }
    @Test void restorationRejectsContractWideAddendumChanges() {
        fixture.payment(date, "100"); var draft = prepared(); var result = preview(draft, Mode.REPLACE);
        var addendum = data.create(Addendum.class); addendum.setContract(fixture.contract); addendum.setNumber("unrelated-new"); addendum.setEffectiveDate(date.plusYears(5)); fixture.save(addendum);
        assertThatThrownBy(() -> workflow.getPreview(draft.operationId(), result.revision())).isInstanceOf(ConcurrentModificationException.class);
        assertThat(store.load(draft.operationId()).revision()).isEqualTo(result.revision());
    }
    @Test void contractWideHiddenAddendumBlocksPreviewWithoutCorrections() {
        var addendum = data.create(Addendum.class); addendum.setContract(fixture.contract); addendum.setNumber("hidden-workflow"); addendum.setEffectiveDate(date.plusYears(5)); fixture.save(addendum);
        var draft = prepared(); restricted("workflow-hidden-addendum");
        assertThatThrownBy(() -> preview(draft, Mode.FILL)).isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
    }
    @Test void shipmentPreviewUsesIndependentHistory() {
        fixture.payment(date, "100"); correction("payment-only", date.plusYears(5));
        var draft = workflow.start(conversation, Kind.SHIPMENT);
        draft = workflow.setRows(draft.operationId(), draft.revision(), List.of(new InputRow(date.toString(), "42", "shipment")), null);
        workflow.selectContract(draft.operationId(), draft.revision(), fixture.contract.getId());
        draft = store.load(draft.operationId());
        assertThat(workflow.allowedModes(draft.operationId())).containsExactly(Mode.FILL);
        var before = domainCounts(); var result = preview(draft, Mode.FILL);
        assertThat(result.kind()).isEqualTo(Kind.SHIPMENT);
        assertThat(result.total()).isEqualByComparingTo("42");
        assertThat(workflow.getPreview(draft.operationId(), result.revision())).isEqualTo(result);
        assertThat(domainCounts()).isEqualTo(before);
    }
    @Test void rejectsForeignProjectAndStaleRevision() {
        try (var other = new RelatedFixtures(data, auth)) {
            var draft = prepared();
            assertThatThrownBy(() -> workflow.selectProject(draft.operationId(), draft.revision(), other.project.getId())).isInstanceOf(RuntimeException.class);
            var result = preview(draft, Mode.FILL);
            assertThatThrownBy(() -> workflow.getPreview(draft.operationId(), draft.revision())).isInstanceOf(ConcurrentModificationException.class);
            assertThat(result.noChanges()).isFalse();
        }
    }
    @Test void hiddenProjectNeverBecomesOnlyActualProject() {
        var hidden = data.create(Project.class); hidden.setName("hidden-workflow"); hidden.setContract(fixture.contract); hidden.setLineOfBusiness(fixture.project.getLineOfBusiness()); fixture.save(hidden);
        var draft = workflow.start(conversation, Kind.PAYMENT);
        restricted("workflow-hidden-project");
        assertThatThrownBy(() -> workflow.selectContract(draft.operationId(), draft.revision(), fixture.contract.getId())).isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
        login(); assertThat(store.load(draft.operationId()).content().contractId()).isNull();
    }
    @Test void hiddenHistoryAndAttributesBlockPreview() {
        fixture.payment(date, "100"); correction("hidden-workflow", date.plusDays(1)); var draft = prepared();
        restricted("workflow-hidden-addendum");
        assertThatThrownBy(() -> preview(draft, Mode.ADDENDUM)).isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
        var user = internal.create(User.class); user.setUsername(owner); DashboardFixtures.authenticate(user, "ROLE_related-payment-reader");
        assertThatThrownBy(() -> preview(draft, Mode.REPLACE)).isInstanceOf(RuntimeException.class);
    }
    @Test void rowWritePoliciesBlockPreview() {
        var draft = prepared(); restricted("workflow-deny-item-create");
        assertThatThrownBy(() -> preview(draft, Mode.FILL)).isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
    }
    @Test void correctionTotalPredicateRejectsFullProposedSnapshot() {
        fixture.payment(date, "100"); var draft = prepared();
        restricted("workflow-correction-total-cap");
        assertThatThrownBy(() -> preview(draft, Mode.ADDENDUM)).isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
        login();
        assertThat(store.load(draft.operationId()).status()).isEqualTo(ScheduleImportStore.Status.COLLECTING);
    }
    @Test void addendumPredicateSeesCompleteCorrectionComposition() {
        fixture.payment(date, "100"); var draft = prepared();
        var before = domainCounts(); var base = persistedBaseState();
        restricted("workflow-complete-addendum");
        var result = preview(draft, Mode.ADDENDUM);
        assertThat(workflow.getPreview(draft.operationId(), result.revision())).isEqualTo(result);
        assertThat(domainCounts()).isEqualTo(before);
        assertThat(persistedBaseState()).isEqualTo(base);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=Mode.class, names={"CREATE", "FILL", "REPLACE"})
    void basePredicateSeesCompleteProposedItemsWithoutChangingPersistentGraph(Mode mode) {
        if (mode == Mode.CREATE) data.remove(fixture.payment);
        if (mode == Mode.REPLACE) fixture.payment(date, "100");
        var draft = prepared(); var before = domainCounts(); var base = persistedBaseState();
        restricted("workflow-complete-base");
        var result = preview(draft, mode);
        assertThat(workflow.getPreview(draft.operationId(), result.revision())).isEqualTo(result);
        assertThat(domainCounts()).isEqualTo(before);
        assertThat(persistedBaseState()).isEqualTo(base);
    }
    List<String> persistedBaseState() {
        var headers = internal.load(PaymentSchedule.class).query("select e from PaymentSchedule e where e.project.id = :project")
                .parameter("project", fixture.project.getId()).fetchPlan(fp -> fp.addFetchPlan(FetchPlan.LOCAL).add("items", FetchPlan.LOCAL)).list();
        return headers.stream().flatMap(h -> java.util.stream.Stream.concat(java.util.stream.Stream.of(h.getId() + ":" + h.getVersion()),
                h.getItems().stream().map(i -> i.getId() + ":" + i.getVersion() + ":" + i.getItemDate() + ":" + i.getAmount())))
                .sorted().toList();
    }
    @Test void invalidRowsAreNotStored() {
        var draft = workflow.start(conversation, Kind.PAYMENT);
        assertThatThrownBy(() -> workflow.setRows(draft.operationId(), draft.revision(), List.of(new InputRow(date.toString(), "1.001", "bad")), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.load(draft.operationId()).revision()).isEqualTo(draft.revision());
    }
    @Test void contractSearchUsesReadableServerCaptions() {
        var draft = workflow.start(conversation, Kind.PAYMENT);
        assertThat(workflow.findContracts(draft.operationId(), fixture.contract.getInternalID())).containsExactly(new ScheduleImportWorkflow.Choice(fixture.contract.getId(), fixture.contract.getInternalID()));
    }
    void restricted(String role) {var user = internal.create(User.class); user.setUsername(owner); DashboardFixtures.restricted(user, role);}
    void correction(String number, LocalDate when) {
        var addendum = data.create(Addendum.class); addendum.setContract(fixture.contract); addendum.setNumber(number); addendum.setEffectiveDate(when); addendum = fixture.save(addendum);
        var correction = data.create(PaymentScheduleCorrection.class); correction.setProject(fixture.project); correction.setAddendum(addendum); fixture.save(correction);
    }
    List<Long> domainCounts() { return java.util.stream.Stream.of("PaymentSchedule", "PaymentScheduleItem", "ShipmentSchedule", "ShipmentScheduleItem", "Addendum", "PaymentScheduleCorrection", "PaymentScheduleCorrectionItem", "ShipmentScheduleCorrection", "ShipmentScheduleCorrectionItem").map(e -> internal.loadValue("select count(e) from " + e + " e", Long.class).one()).toList(); }
    @io.jmix.security.role.annotation.RowLevelRole(name="Workflow hidden project", code="workflow-hidden-project")
    public interface HiddenProject {
        @io.jmix.security.role.annotation.JpqlRowLevelPolicy(entityClass=Project.class, where="{E}.name <> 'hidden-workflow'") void projects();
    }
    @io.jmix.security.role.annotation.RowLevelRole(name="Workflow hidden addendum", code="workflow-hidden-addendum")
    public interface HiddenAddendum {
        @io.jmix.security.role.annotation.JpqlRowLevelPolicy(entityClass=Addendum.class, where="{E}.number <> 'hidden-workflow'") void addenda();
    }
    @io.jmix.security.role.annotation.RowLevelRole(name="Workflow deny item create", code="workflow-deny-item-create")
    public interface DenyItemCreate {
        @io.jmix.security.role.annotation.PredicateRowLevelPolicy(entityClass=PaymentScheduleItem.class, actions=io.jmix.security.model.RowLevelPolicyAction.CREATE)
        default io.jmix.security.model.RowLevelBiPredicate<PaymentScheduleItem, org.springframework.context.ApplicationContext> items() { return (item, ctx) -> false; }
    }
    @io.jmix.security.role.annotation.RowLevelRole(name="Workflow correction total cap", code="workflow-correction-total-cap")
    public interface CorrectionTotalCap {
        @io.jmix.security.role.annotation.PredicateRowLevelPolicy(entityClass=PaymentScheduleCorrection.class, actions=io.jmix.security.model.RowLevelPolicyAction.CREATE)
        default io.jmix.security.model.RowLevelBiPredicate<PaymentScheduleCorrection, org.springframework.context.ApplicationContext> correction() {
            return (header, ctx) -> header.getItems() == null || header.getItems().stream().map(PaymentScheduleCorrectionItem::getAmount)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add).compareTo(new java.math.BigDecimal("100")) <= 0;
        }
    }
    @io.jmix.security.role.annotation.RowLevelRole(name="Workflow complete addendum", code="workflow-complete-addendum")
    public interface CompleteAddendum {
        @io.jmix.security.role.annotation.PredicateRowLevelPolicy(entityClass=Addendum.class, actions=io.jmix.security.model.RowLevelPolicyAction.CREATE)
        default io.jmix.security.model.RowLevelBiPredicate<Addendum, org.springframework.context.ApplicationContext> addendum() {
            return (addendum, ctx) -> addendum.getPaymentScheduleCorrections() != null && addendum.getPaymentScheduleCorrections().size() == 1
                    && addendum.getShipmentScheduleCorrections() != null && addendum.getShipmentScheduleCorrections().isEmpty()
                    && addendum.getPaymentScheduleCorrections().getFirst().getAddendum() == addendum
                    && completeCorrection(addendum.getPaymentScheduleCorrections().getFirst());
        }
        @io.jmix.security.role.annotation.PredicateRowLevelPolicy(entityClass=PaymentScheduleCorrection.class, actions=io.jmix.security.model.RowLevelPolicyAction.CREATE)
        default io.jmix.security.model.RowLevelBiPredicate<PaymentScheduleCorrection, org.springframework.context.ApplicationContext> correction() {
            return (header, ctx) -> completeCorrection(header) && header.getAddendum().getPaymentScheduleCorrections().getFirst() == header;
        }
        private static boolean completeCorrection(PaymentScheduleCorrection header) {
            return header.getItems() != null && header.getItems().size() == 1 && header.getItems().getFirst().getCorrection() == header
                    && header.getItems().getFirst().getAmount().compareTo(new java.math.BigDecimal("120")) == 0;
        }
    }
    @io.jmix.security.role.annotation.RowLevelRole(name="Workflow complete base", code="workflow-complete-base")
    public interface CompleteBase {
        @io.jmix.security.role.annotation.PredicateRowLevelPolicy(entityClass=PaymentSchedule.class, actions={io.jmix.security.model.RowLevelPolicyAction.CREATE, io.jmix.security.model.RowLevelPolicyAction.UPDATE})
        default io.jmix.security.model.RowLevelBiPredicate<PaymentSchedule, org.springframework.context.ApplicationContext> header() {
            return (header, ctx) -> header.getItems() != null && header.getItems().size() == 1 && header.getItems().getFirst().getSchedule() == header
                    && header.getItems().getFirst().getAmount().compareTo(new java.math.BigDecimal("120")) == 0
                    && header.getProject().getPaymentSchedule() == header;
        }
    }
}
