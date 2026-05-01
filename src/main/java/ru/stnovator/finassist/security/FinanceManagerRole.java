package ru.stnovator.finassist.security;

import io.jmix.security.model.EntityAttributePolicyAction;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.role.annotation.EntityAttributePolicy;
import io.jmix.security.role.annotation.EntityPolicy;
import io.jmix.security.role.annotation.ResourceRole;
import io.jmix.securityflowui.role.annotation.MenuPolicy;
import io.jmix.securityflowui.role.annotation.ViewPolicy;
import ru.stnovator.finassist.entity.*;

@ResourceRole(name = "FinanceManagerRole", code = FinanceManagerRole.CODE)
public interface FinanceManagerRole {
    String CODE = "finance-manager-role";

    @MenuPolicy(menuIds = {"Contract.list", "Addendum.list", "Customer.list", "Project.list", "PaymentSchedule.list", "ShipmentSchedule.list", "PaymentScheduleCorrection.list", "ShipmentScheduleCorrection.list", "LineOfBusiness.list", "report_Report.list", "report_ReportRunView", "report_ReportGroup.list", "datatl_entityInspectorListView", "datatl_dataModelListView"})
    @ViewPolicy(viewIds = {"Contract.list", "Addendum.list", "Customer.list", "Project.list", "PaymentSchedule.list", "ShipmentSchedule.list", "PaymentScheduleCorrection.list", "ShipmentScheduleCorrection.list", "LineOfBusiness.list", "report_Report.list", "report_ReportRunView", "report_ReportGroup.list", "datatl_entityInspectorListView", "datatl_dataModelListView"})
    void screens();

    @EntityAttributePolicy(entityClass = Addendum.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = Addendum.class, actions = EntityPolicyAction.ALL)
    void addendum();

    @EntityAttributePolicy(entityClass = Contract.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = Contract.class, actions = EntityPolicyAction.ALL)
    void contract();

    @EntityAttributePolicy(entityClass = Customer.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = Customer.class, actions = EntityPolicyAction.ALL)
    void customer();

    @EntityAttributePolicy(entityClass = LineOfBusiness.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = LineOfBusiness.class, actions = EntityPolicyAction.ALL)
    void lineOfBusiness();

    @EntityAttributePolicy(entityClass = PaymentSchedule.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = PaymentSchedule.class, actions = EntityPolicyAction.ALL)
    void paymentSchedule();

    @EntityAttributePolicy(entityClass = PaymentScheduleCorrection.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = PaymentScheduleCorrection.class, actions = EntityPolicyAction.ALL)
    void paymentScheduleCorrection();

    @EntityAttributePolicy(entityClass = PaymentScheduleCorrectionItem.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = PaymentScheduleCorrectionItem.class, actions = EntityPolicyAction.ALL)
    void paymentScheduleCorrectionItem();

    @EntityAttributePolicy(entityClass = PaymentScheduleItem.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = PaymentScheduleItem.class, actions = EntityPolicyAction.ALL)
    void paymentScheduleItem();

    @EntityAttributePolicy(entityClass = Project.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = Project.class, actions = EntityPolicyAction.ALL)
    void project();

    @EntityAttributePolicy(entityClass = ShipmentSchedule.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = ShipmentSchedule.class, actions = EntityPolicyAction.ALL)
    void shipmentSchedule();

    @EntityAttributePolicy(entityClass = ShipmentScheduleCorrection.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = ShipmentScheduleCorrection.class, actions = EntityPolicyAction.ALL)
    void shipmentScheduleCorrection();

    @EntityAttributePolicy(entityClass = ShipmentScheduleCorrectionItem.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = ShipmentScheduleCorrectionItem.class, actions = EntityPolicyAction.ALL)
    void shipmentScheduleCorrectionItem();

    @EntityAttributePolicy(entityClass = ShipmentScheduleItem.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = ShipmentScheduleItem.class, actions = EntityPolicyAction.ALL)
    void shipmentScheduleItem();

    @EntityAttributePolicy(entityClass = User.class, attributes = "*", action = EntityAttributePolicyAction.VIEW)
    @EntityPolicy(entityClass = User.class, actions = EntityPolicyAction.READ)
    void user();
}