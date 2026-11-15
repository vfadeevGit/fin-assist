package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitoolsflowuidata.entity.AiConversationEntity;
import io.jmix.core.*;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.entity.*;
import ru.stnovator.finassist.test_support.DashboardFixtures;
import java.io.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class ScheduleAttachmentServiceTest {
    @Autowired ScheduleAttachmentService service;
    @Autowired io.jmix.aitoolsflowui.service.AiConversationService nativeConversations;
    @Autowired ScheduleImportStore store;
    @Autowired FileStorageLocator storageLocator;
    @Autowired ScheduleImportCleanup cleanup;
    @Autowired ScheduleImportProperties properties;
    @Autowired UnconstrainedDataManager data;
    @Autowired SystemAuthenticator auth;
    final String owner="attachment-owner-"+UUID.randomUUID();
    final List<UUID> conversations=new ArrayList<>();
    UUID conversation;
    @BeforeEach void setup() {auth.begin("admin");conversation=conversation(owner);login(owner);}
    UUID conversation(String username) {var c=data.create(AiConversationEntity.class);c.setTitle("file test");c.setUsername(username);c=data.save(c);conversations.add(c.getId());return c.getId();}
    void login(String username){var u=data.create(User.class);u.setUsername(username);DashboardFixtures.authenticate(u,"ROLE_finance-manager-role");}
    @AfterEach void teardown(){
        properties.setMaxFileBytes(25L*1024*1024);
        data.load(AiScheduleImport.class).all().list().stream().filter(e->owner.equals(e.getOwner())).forEach(data::remove);
        login(owner);
        data.load(AiScheduleAttachment.class).all().list().stream().filter(e->owner.equals(e.getOwner())).forEach(e->{storageLocator.getByName(e.getFileRef().getStorageName()).removeFile(e.getFileRef());data.remove(data.load(AiScheduleAttachment.class).id(e.getId()).one());});
        conversations.forEach(id->data.load(AiConversationEntity.class).id(id).optional().ifPresent(data::remove));auth.end();
    }
    UUID stage(){return service.stage(conversation,"schedule.csv",new ByteArrayInputStream("date;amount".getBytes()));}
    ScheduleImportStore.DraftContent content(UUID id){return new ScheduleImportStore.DraftContent(null,id,null,null,List.of(),null,null,null,null,null,null);}
    @Test void attachmentIdCannotCrossConversation(){
        UUID attachment=stage(); UUID other=conversation(owner);UUID operation=store.create(other);
        assertThatThrownBy(()->store.update(operation,0,content(attachment))).isInstanceOf(SecurityException.class);
        login("other-user");
        assertThatThrownBy(()->service.open(attachment)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.remove(attachment)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.stage(conversation,"a.csv",InputStream.nullInputStream())).isInstanceOf(SecurityException.class);
    }
    @Test void substitutedEffectiveOwnerCanStageAndOpenButOriginalActorCannot() throws Exception {
        var operator=data.create(User.class);operator.setUsername("attachment-operator-"+UUID.randomUUID());DashboardFixtures.authenticate(operator,"ROLE_system-full-access");
        var original=Objects.requireNonNull(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
        var effective=data.create(User.class);effective.setUsername(owner);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new io.jmix.core.security.impl.SubstitutedUserAuthenticationToken(original,effective,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_finance-manager-role"))));
        UUID attachment=stage();assertThat(data.load(AiScheduleAttachment.class).id(attachment).one().getOwner()).isEqualTo(owner);
        try(var input=service.open(attachment)){assertThat(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("date;amount");}
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(original);
        assertThatThrownBy(()->{try(var input=service.open(attachment)){input.read();}}).isInstanceOf(SecurityException.class);assertThatThrownBy(()->service.remove(attachment)).isInstanceOf(SecurityException.class);
    }
    @Test void storedFileSurvivesReloadAndHasStableLink() throws Exception {
        UUID attachment=stage();UUID operation=store.create(conversation);store.update(operation,0,content(attachment));
        try(var stream=service.open(store.load(operation).content().source())) {assertThat(new String(stream.readAllBytes())).isEqualTo("date;amount");}
        assertThat(data.load(AiScheduleImport.class).id(operation).one().getAttachment().getId()).isEqualTo(attachment);
        assertThatThrownBy(()->service.remove(attachment)).isInstanceOf(IllegalStateException.class);
    }
    @Test void enforcesByteLimitBeforePersistingFile() {
        properties.setMaxFileBytes(2);
        assertThatThrownBy(this::stage).isInstanceOf(IllegalArgumentException.class);
        assertThat(data.load(AiScheduleAttachment.class).all().list().stream().filter(e->owner.equals(e.getOwner()))).isEmpty();
    }
    void deleteNativeConversation() {nativeConversations.remove(nativeConversations.loadConversation(conversation));}
    @Test void nativeConversationDeletionCancelsActiveImportAndRetainsFileGracePeriod() {
        UUID attachment=stage();UUID operation=store.create(conversation);store.update(operation,0,content(attachment));
        deleteNativeConversation();
        assertThat(data.load(AiConversationEntity.class).id(conversation).optional()).isEmpty();
        var receipt=store.load(operation);assertThat(receipt.status()).isEqualTo(ScheduleImportStore.Status.CANCELLED);
        assertThat(receipt.conversationId()).isEqualTo(conversation);assertThat(receipt.revision()).isEqualTo(2);
        assertThatThrownBy(()->store.update(operation,2,content(attachment))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.open(attachment)).isInstanceOf(SecurityException.class);
        cleanup.run();assertThat(data.load(AiScheduleAttachment.class).id(attachment).one().getFileDeletedAt()).isNull();
        var file=data.load(AiScheduleAttachment.class).id(attachment).one();file.setRemovalRequestedAt(OffsetDateTime.now().minusDays(2));data.saveWithoutReload(file);
        var entity=data.load(AiScheduleImport.class).id(operation).one();entity.setTerminalAt(OffsetDateTime.now().minusDays(2));data.saveWithoutReload(entity);
        cleanup.run();assertThat(data.load(AiScheduleAttachment.class).id(attachment).one().getFileDeletedAt()).isNotNull();
        assertThat(store.load(operation).operationId()).isEqualTo(operation);
    }
    @Test void nativeConversationDeletionPreservesSavedReceipt() {
        UUID attachment=stage();UUID operation=store.create(conversation);store.update(operation,0,content(attachment));
        var receipt=data.load(AiScheduleImport.class).id(operation).one();UUID result=UUID.randomUUID();
        receipt.setStatus("SAVED");receipt.setResultId(result);receipt.setTerminalAt(OffsetDateTime.now());data.saveWithoutReload(receipt);
        deleteNativeConversation();
        assertThat(store.load(operation).status()).isEqualTo(ScheduleImportStore.Status.SAVED);
        assertThat(store.load(operation).resultId()).isEqualTo(result);assertThat(store.load(operation).conversationId()).isEqualTo(conversation);
        cleanup.run();assertThat(data.load(AiScheduleAttachment.class).id(attachment).one().getFileDeletedAt()).isNull();
    }
    @Test void nativeConversationDeletionAfterUnlinkedStagingPreservesMetadata() {
        UUID attachment=stage();deleteNativeConversation();
        assertThat(data.load(AiScheduleAttachment.class).id(attachment).optional()).isPresent();
        var requested=data.load(AiScheduleAttachment.class).id(attachment).one().getRemovalRequestedAt();
        cleanup.run();cleanup.run();
        var file=data.load(AiScheduleAttachment.class).id(attachment).one();
        assertThat(file.getRemovalRequestedAt()).isEqualTo(requested);assertThat(file.getFileDeletedAt()).isNull();
        file.setRemovalRequestedAt(OffsetDateTime.now().minusDays(2));data.saveWithoutReload(file);
        cleanup.run();assertThat(data.load(AiScheduleAttachment.class).id(attachment).one().getFileDeletedAt()).isNotNull();
    }
    @Test void deletionRollbackRestoresConversationAndActiveDraft() {
        UUID attachment=stage();UUID operation=store.create(conversation);store.update(operation,0,content(attachment));
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThatThrownBy(()->transaction.executeWithoutResult(status->{deleteNativeConversation();throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(data.load(AiConversationEntity.class).id(conversation).optional()).isPresent();
        assertThat(store.load(operation).status()).isEqualTo(ScheduleImportStore.Status.COLLECTING);
        assertThat(store.load(operation).revision()).isEqualTo(1);
        assertThat(data.load(AiScheduleAttachment.class).id(attachment).one().getRemovalRequestedAt()).isNull();
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Test void cleanupNeverDeletesActiveAttachment() {
        UUID attachment=stage();UUID operation=store.create(conversation);store.update(operation,0,content(attachment));
        var old=data.load(AiScheduleAttachment.class).id(attachment).one();old.setStagedAt(OffsetDateTime.now().minusDays(4));data.saveWithoutReload(old);
        auth.end(); cleanup.run();auth.begin("admin");login(owner);
        assertThat(data.load(AiScheduleAttachment.class).id(attachment).one().getFileDeletedAt()).isNull();
        assertThat(store.load(operation).status()).isEqualTo(ScheduleImportStore.Status.COLLECTING);
    }
    @Test void cleanupExpiresStaleDraftAndRetainsReceiptBeforeDeletingFile() {
        UUID attachment=stage();UUID operation=store.create(conversation);store.update(operation,0,content(attachment));
        var old=data.load(AiScheduleImport.class).id(operation).one();old.setTouchedAt(OffsetDateTime.now().minusDays(2));data.saveWithoutReload(old);
        auth.end();cleanup.run();auth.begin("admin");login(owner);
        assertThat(store.load(operation).status()).isEqualTo(ScheduleImportStore.Status.EXPIRED);
        assertThat(data.load(AiScheduleAttachment.class).id(attachment).one().getFileDeletedAt()).isNull();
        old=data.load(AiScheduleImport.class).id(operation).one();old.setTerminalAt(OffsetDateTime.now().minusDays(2));data.saveWithoutReload(old);
        auth.end();cleanup.run();auth.begin("admin");login(owner);
        assertThat(data.load(AiScheduleAttachment.class).id(attachment).one().getFileDeletedAt()).isNotNull();
        assertThat(store.load(operation).operationId()).isEqualTo(operation);
    }
}
