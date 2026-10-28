package ru.stnovator.finassist.test_support;

import io.jmix.core.*;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import ru.stnovator.finassist.entity.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public class RelatedFixtures implements AutoCloseable {
    private final DataManager data;
    private final SystemAuthenticator auth;
    private final List<Object> created = new ArrayList<>();
    public Customer customer;
    public Contract contract;
    public Project project;
    public ShipmentSchedule shipment;
    public PaymentSchedule payment;
    public RelatedFixtures(DataManager data, SystemAuthenticator auth) {
        this.data = data;
        this.auth = auth;
        auth.runWithUser("admin", () -> {
            customer = data.create(Customer.class); customer.setName("related-" + UUID.randomUUID()); customer = save(customer);
            contract = data.create(Contract.class); contract.setCustomer(customer); contract.setInternalID(customer.getName());
            contract.setStartDate(LocalDate.of(2026,1,1)); contract.setEndDate(LocalDate.of(2027,1,1)); contract = save(contract);
            var business = data.create(LineOfBusiness.class); business.setName(customer.getName()); business = save(business);
            project = data.create(Project.class); project.setName(customer.getName()); project.setContract(contract); project.setLineOfBusiness(business); project = save(project);
            shipment = data.create(ShipmentSchedule.class); shipment.setProject(project); shipment = save(shipment);
            payment = data.create(PaymentSchedule.class); payment.setProject(project); payment = save(payment);
        });
    }
    public <T> T save(T entity) { T saved = data.save(entity); created.add(saved); return saved; }
    public ShipmentScheduleItem shipment(LocalDate date, String amount) {
        var item = data.create(ShipmentScheduleItem.class); item.setSchedule(shipment); item.setItemDate(date); item.setAmount(new BigDecimal(amount)); return save(item);
    }
    public PaymentScheduleItem payment(LocalDate date, String amount) {
        var item = data.create(PaymentScheduleItem.class); item.setSchedule(payment); item.setItemDate(date); item.setAmount(new BigDecimal(amount)); return save(item);
    }
    @Override public void close() {
        auth.runWithUser("admin", () -> {
            Collections.reverse(created);
            for (Object entity : created) data.load(Id.of(entity)).hint(PersistenceHints.SOFT_DELETION,false).optional()
                    .ifPresent(e -> data.save(new SaveContext().removing(e).setHint(PersistenceHints.SOFT_DELETION,false)));
        });
    }
}
