package ru.stnovator.finassist.security;

import io.jmix.security.model.EntityAttributePolicyAction;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.role.annotation.*;
import ru.stnovator.finassist.entity.*;

/** Technical records have no generic CRUD surface. Mutations go through owned server services. */
@ResourceRole(name="AI schedule imports",code=AiScheduleImportRole.CODE)
public interface AiScheduleImportRole {
    String CODE="ai-schedule-import";
    @EntityPolicy(entityClass=AiScheduleImport.class,actions=EntityPolicyAction.READ)
    @EntityAttributePolicy(entityClass=AiScheduleImport.class,attributes="*",action=EntityAttributePolicyAction.VIEW)
    void imports();
    @EntityPolicy(entityClass=AiScheduleAttachment.class,actions=EntityPolicyAction.READ)
    @EntityAttributePolicy(entityClass=AiScheduleAttachment.class,attributes={"id","version","owner","conversation","fileName","sizeBytes","stagedAt","removalRequestedAt","fileDeletedAt","createdBy","createdDate","lastModifiedBy","lastModifiedDate"},action=EntityAttributePolicyAction.VIEW)
    void attachments();
}
