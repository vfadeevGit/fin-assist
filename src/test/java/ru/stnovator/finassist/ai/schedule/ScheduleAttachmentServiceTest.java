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
    @Autowired ScheduleImportStore store;
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
        data.load(AiScheduleAttachment.class).all().list().stream().filter(e->owner.equals(e.getOwner())).forEach(e->{service.remove(e.getId());data.remove(data.load(AiScheduleAttachment.class).id(e.getId()).one());});
        conversations.forEach(id->data.remove(data.load(AiConversationEntity.class).id(id).one()));auth.end();
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
