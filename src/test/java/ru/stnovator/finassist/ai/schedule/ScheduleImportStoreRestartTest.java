package ru.stnovator.finassist.ai.schedule;

import io.jmix.aitoolsflowuidata.entity.AiConversationEntity;
import io.jmix.core.*;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import ru.stnovator.finassist.entity.*;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** BeforeAll persists in context A; BEFORE_METHOD explicitly closes A and reinjects context B. */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduleImportStoreRestartTest {
    @Autowired ApplicationContext context;
    @Autowired UnconstrainedDataManager data;
    @Autowired ScheduleImportStore store;
    @Autowired ScheduleAttachmentService files;
    @Autowired SystemAuthenticator auth;
    ApplicationContext originalContext;
    UUID conversation,operation,attachment;
    @BeforeAll void persistInOriginalContext() {
        originalContext=context;
        auth.runWithUser("admin",()-> {
            var c=data.create(AiConversationEntity.class);c.setUsername("admin");c.setTitle("restart test");conversation=data.save(c).getId();
            attachment=files.stage(conversation,"restart.csv",new ByteArrayInputStream("persistent-file".getBytes()));
            operation=store.create(conversation);
            store.update(operation,0,new ScheduleImportStore.DraftContent(null,attachment,null,null,List.of(),null,null,null,null,null,null));
        });
    }
    @Test
    @DirtiesContext(methodMode=DirtiesContext.MethodMode.BEFORE_METHOD)
    void persistedRevisionAndAttachmentSurviveContextRestart() {
        assertThat(context).isNotSameAs(originalContext);
        auth.runWithUser("admin",()-> {
            var draft=store.load(operation);assertThat(draft.revision()).isEqualTo(1);assertThat(draft.content().source()).isEqualTo(attachment);
            try(var stream=files.open(attachment)) {assertThat(new String(stream.readAllBytes())).isEqualTo("persistent-file");}
            catch(java.io.IOException ex) {throw new java.io.UncheckedIOException(ex);}
        });
    }
    @AfterAll void cleanup() {
        auth.runWithUser("admin",()-> {
            if(operation!=null)data.load(AiScheduleImport.class).id(operation).optional().ifPresent(data::remove);
            if(attachment!=null){files.remove(attachment);data.remove(data.load(AiScheduleAttachment.class).id(attachment).one());}
            if(conversation!=null)data.remove(data.load(AiConversationEntity.class).id(conversation).one());
        });
    }
}
