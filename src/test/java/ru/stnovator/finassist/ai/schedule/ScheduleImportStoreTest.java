package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitoolsflowuidata.entity.AiConversationEntity;
import io.jmix.core.*;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.ai.BusinessEntityRegistry;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.test_support.DashboardFixtures;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static ru.stnovator.finassist.ai.schedule.ScheduleImportTypes.*;

@SpringBootTest
class ScheduleImportStoreTest {
    @Autowired ScheduleImportStore store;
    @Autowired ScheduleAttachmentService attachments;
    @Autowired UnconstrainedDataManager internal;
    @Autowired DataManager data;
    @Autowired SystemAuthenticator auth;
    @Autowired BusinessEntityRegistry registry;
    UUID conversation;
    final String owner="schedule-owner-"+UUID.randomUUID();
    @BeforeEach void setup() {
        auth.begin("admin");
        var c=internal.create(AiConversationEntity.class); c.setTitle("import test"); c.setUsername(owner);
        conversation=internal.save(c).getId();
        login(owner);
    }
    void login(String username) { var u=internal.create(User.class);u.setUsername(username); DashboardFixtures.authenticate(u,"ROLE_finance-manager-role"); }
    @AfterEach void cleanup() {
        internal.load(AiScheduleImport.class).all().list().stream().filter(e->owner.equals(e.getOwner())).forEach(internal::remove);
        internal.load(AiScheduleAttachment.class).all().list().stream().filter(e->owner.equals(e.getOwner())).forEach(e->{
            login(owner); attachments.remove(e.getId()); internal.remove(internal.load(AiScheduleAttachment.class).id(e.getId()).one());
        });
        internal.remove(internal.load(AiConversationEntity.class).id(conversation).one()); auth.end();
    }
    ScheduleImportStore.DraftContent content(UUID source) {
        return new ScheduleImportStore.DraftContent(Kind.PAYMENT,source,null,
            new ScheduleTableTypes.Mapping("csv",0,1,1,Set.of(),new ParseOptions("dd.MM.uuuu",',',' '),new ScheduleTableTypes.CsvOptions("windows-1251",';')),
            List.of(new Row(LocalDate.of(2026,11,15),new java.math.BigDecimal("12.30"))),UUID.randomUUID(),UUID.randomUUID(),Mode.FILL,null,null,null);
    }
    @Test void ownerCanResumeDraft() {
        UUID id=store.create(conversation); var updated=store.update(id,0,content(null));
        var resumed=store.load(id);
        assertThat(resumed.content()).isEqualTo(updated.content()); assertThat(resumed.revision()).isEqualTo(1);
        assertThat(resumed.conversationId()).isEqualTo(conversation);
        assertThat(resumed.status()).isEqualTo(ScheduleImportStore.Status.COLLECTING);
        assertThat(internal.load(AiScheduleImport.class).id(id).one().getContentJson()).contains("schemaVersion");
    }
    @Test void otherUserCannotReadOrMutate() {
        UUID id=store.create(conversation); login("intruder");
        assertThatThrownBy(()->store.load(id)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->store.update(id,0,content(null))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->store.cancel(id,0)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->store.create(conversation)).isInstanceOf(SecurityException.class);
        assertThat(data.load(AiScheduleImport.class).id(id).optional()).isEmpty();
        var tampered=internal.load(AiScheduleImport.class).id(id).one(); tampered.setOwner("intruder"); tampered.setRevision(99L);tampered.setStatus("SAVED");
        assertThatThrownBy(()->data.save(tampered)).isInstanceOf(RuntimeException.class);
    }
    @Test void directDataManagerCannotForgeReceiptEvenWithFullResourceAccess() {
        UUID id=store.create(conversation);
        var user=internal.create(User.class);user.setUsername(owner);
        DashboardFixtures.authenticate(user,"ROLE_system-full-access");
        var row=data.load(AiScheduleImport.class).id(id).one();
        row.setStatus("SAVED");row.setRevision(98L);row.setResultId(UUID.randomUUID());
        assertThatThrownBy(()->data.save(row)).isInstanceOf(RuntimeException.class);
        login("intruder");var intruder=internal.create(User.class);intruder.setUsername("intruder");
        DashboardFixtures.authenticate(intruder,"ROLE_system-full-access");
        assertThat(data.load(AiScheduleImport.class).id(id).optional()).isEmpty();
        assertThatThrownBy(()->data.loadValue("select e.contentJson from AiScheduleImport e",String.class).list()).isInstanceOf(RuntimeException.class);
        assertThat(storeStatus(id)).isEqualTo("COLLECTING");
    }
    @Test void entityQueriesCannotProbeForeignTechnicalSubqueries() {
        UUID id=store.create(conversation);store.update(id,0,content(null));
        var admin=internal.load(User.class).query("select u from User u where u.username = 'admin'").one();
        DashboardFixtures.authenticate(admin,"ROLE_system-full-access");
        assertThatThrownBy(()->data.load(User.class).query("select u from User u where u.username = :me and exists (select i.id from AiScheduleImport i where i.owner <> :me and i.contentJson like :guess)")
                .parameter("me","admin").parameter("guess","%PAYMENT%").list()).isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
        // This legitimate business-only EXISTS remains available.
        assertThat(data.load(User.class).query("select u from User u where u.username = :me and exists (select v.id from User v where v.id = u.id)")
                .parameter("me","admin").list()).extracting(User::getUsername).containsExactly("admin");
        login(owner);var user=internal.create(User.class);user.setUsername(owner);DashboardFixtures.authenticate(user,"ROLE_system-full-access");
        assertThatThrownBy(()->data.load(AiScheduleImport.class).query("select i from AiScheduleImport i where exists (select hidden.id from AiScheduleImport hidden where hidden.contentJson like :guess)")
                .parameter("guess","%PAYMENT%").list()).isInstanceOf(io.jmix.core.security.AccessDeniedException.class);
    }
    String storeStatus(UUID id) {return internal.load(AiScheduleImport.class).id(id).one().getStatus();}
    @Test void messageProvenanceCannotCrossConversation() {
        var other=internal.create(AiConversationEntity.class);other.setUsername(owner);other.setTitle("other");other=internal.save(other);
        var message=internal.create(io.jmix.aitoolsflowuidata.entity.AiChatMessageEntity.class);
        message.setConversation(other);message.setType("user");message.setContent("2026-11-15 12.30");message=internal.save(message);
        try {
            UUID operation=store.create(conversation);var c=content(null);var messageId=message.getId();
            var forged=new ScheduleImportStore.DraftContent(c.kind(),null,messageId,c.mapping(),c.rows(),c.contractId(),c.projectId(),c.mode(),null,null,null);
            assertThatThrownBy(()->store.update(operation,0,forged)).isInstanceOf(SecurityException.class);
        } finally {internal.remove(message);internal.remove(other);}
    }
    @Test void revisionRejectsSecondTab() {
        UUID id=store.create(conversation);store.update(id,0,content(null));
        assertThatThrownBy(()->store.update(id,0,content(null))).isInstanceOf(java.util.ConcurrentModificationException.class);
        assertThatThrownBy(()->store.cancel(id,0)).isInstanceOf(java.util.ConcurrentModificationException.class);
    }
    @Test void previewTransitionIsInternalAndEditsInvalidateIt() {
        UUID id=store.create(conversation);store.update(id,0,content(null));
        store.markPreviewReady(id,1,"fingerprint");
        assertThat(store.load(id).status()).isEqualTo(ScheduleImportStore.Status.PREVIEW_READY);
        var revised=store.update(id,2,content(null));
        assertThat(revised.status()).isEqualTo(ScheduleImportStore.Status.COLLECTING);
        assertThat(revised.content().fingerprint()).isNull();
    }
    @Test void cancelDoesNotCreateDomainRecords() {
        long before=internal.loadValue("select count(e) from Addendum e",Long.class).one();
        UUID id=store.create(conversation);store.cancel(id,0);
        assertThat(store.load(id).status()).isEqualTo(ScheduleImportStore.Status.CANCELLED);
        assertThat(internal.loadValue("select count(e) from Addendum e",Long.class).one()).isEqualTo(before);
        assertThatThrownBy(()->store.update(id,1,content(null))).isInstanceOf(IllegalStateException.class);
    }
    @Test void technicalEntitiesAreNotDiscoverable() {
        assertThat(registry.find("AiScheduleImport")).isEmpty();assertThat(registry.find("AiScheduleAttachment")).isEmpty();
    }
}
