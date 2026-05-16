package ru.stnovator.finassist.report;

import io.jmix.core.DataManager;
import io.jmix.reports.annotation.InputParameterDef;
import io.jmix.reports.yarg.structure.BandData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.entity.Contract;
import ru.stnovator.finassist.entity.ContractType;
import ru.stnovator.finassist.entity.Customer;
import ru.stnovator.finassist.entity.LineOfBusiness;
import ru.stnovator.finassist.entity.PaymentSchedule;
import ru.stnovator.finassist.entity.PaymentScheduleCorrection;
import ru.stnovator.finassist.entity.PaymentScheduleCorrectionItem;
import ru.stnovator.finassist.entity.PaymentScheduleItem;
import ru.stnovator.finassist.entity.Project;
import ru.stnovator.finassist.entity.ShipmentSchedule;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrection;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrectionItem;
import ru.stnovator.finassist.entity.ShipmentScheduleItem;
import ru.stnovator.finassist.test_support.AuthenticatedAsAdmin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class ListOfContractsTest {
    private static final String REPORT_DATE_ALIAS = "reportDate";

    @Autowired
    DataManager dataManager;

    @Autowired
    ListOfContracts listOfContracts;

    private final List<Object> cleanupEntities = new ArrayList<>();

    @AfterEach
    void tearDown() {
        Collections.reverse(cleanupEntities);
        for (Object entity : cleanupEntities) {
            try {
                dataManager.remove(entity);
            } catch (Exception ignored) {
                // Parent entities may already remove children by cascade.
            }
        }
        cleanupEntities.clear();
    }

    @Test
    void reportParametersUseContractListModeAndBaseDefault() {
        InputParameterDef reportDateParameter = findInputParameter(REPORT_DATE_ALIAS);
        assertThat(reportDateParameter.type().name()).isEqualTo("DATE");
        assertThat(reportDateParameter.defaultDateIsCurrent()).isTrue();
        assertThat(reportDateParameter.required()).isTrue();

        InputParameterDef contractListModeParameter = findInputParameter("contractListMode");
        assertThat(contractListModeParameter.type().name()).isEqualTo("ENUMERATION");
        assertThat(contractListModeParameter.enumerationClass()).isEqualTo(ContractListMode.class);
        assertThat(contractListModeParameter.required()).isTrue();
        assertThat(contractListModeParameter.defaultValue()).isEqualTo("BASE");
    }

    @Test
    void baseCashflowRowsAndTotalsDoNotContainDelta() {
        ContractFixture fixture = createFixture("base");

        createShipmentItem(fixture.shipmentSchedule, LocalDate.of(2026, 1, 10), 100);
        createShipmentItem(fixture.shipmentSchedule, LocalDate.of(2026, 1, 10), 50);
        createShipmentItem(fixture.shipmentSchedule, LocalDate.of(2026, 1, 11), 25);

        createPaymentItem(fixture.paymentSchedule, LocalDate.of(2026, 1, 10), 40);
        createPaymentItem(fixture.paymentSchedule, LocalDate.of(2026, 1, 12), 10);

        BandData projectBand = new BandData("Project");
        projectBand.addData("projectId", fixture.project.getId());
        projectBand.addData("activeAddendumId", null);

        List<Map<String, Object>> rows = listOfContracts.projectCashflowRecordsDataLoader()
                .loadData(null, projectBand, Map.of());
        List<Map<String, Object>> totals = listOfContracts.projectCashflowTotalsDataLoader()
                .loadData(null, projectBand, Map.of());

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0))
                .containsEntry("cashflowDate", LocalDate.of(2026, 1, 10))
                .doesNotContainKey("deltaSum");
        assertThat((BigDecimal) rows.get(0).get("shipmentSum")).isEqualByComparingTo("150");
        assertThat((BigDecimal) rows.get(0).get("paymentSum")).isEqualByComparingTo("40");
        assertThat(rows.get(1))
                .containsEntry("cashflowDate", LocalDate.of(2026, 1, 11))
                .doesNotContainKey("deltaSum");
        assertThat((BigDecimal) rows.get(1).get("shipmentSum")).isEqualByComparingTo("25");
        assertThat((BigDecimal) rows.get(1).get("paymentSum")).isEqualByComparingTo("0");
        assertThat(rows.get(2))
                .containsEntry("cashflowDate", LocalDate.of(2026, 1, 12))
                .doesNotContainKey("deltaSum");
        assertThat((BigDecimal) rows.get(2).get("shipmentSum")).isEqualByComparingTo("0");
        assertThat((BigDecimal) rows.get(2).get("paymentSum")).isEqualByComparingTo("10");

        Map<String, Object> totalsRow = totals.getFirst();
        assertThat(totalsRow).doesNotContainKey("finalDelta");
        assertThat((BigDecimal) totalsRow.get("shipmentTotal")).isEqualByComparingTo("175");
        assertThat((BigDecimal) totalsRow.get("paymentTotal")).isEqualByComparingTo("50");
    }

    @Test
    void baseModeProjectRowShowsBaseSourceAndNoAddendum() {
        ContractFixture fixture = createFixture("base-project");

        BandData contractBand = new BandData("Contract");
        contractBand.addData("contractId", fixture.contract.getId());
        contractBand.addData("selectedAddendumId", null);

        Map<String, Object> projectRow = listOfContracts.projectsDataLoader()
                .loadData(null, contractBand, Map.of(
                        "project", fixture.project,
                        "contractListMode", ContractListMode.BASE
                ))
                .getFirst();

        assertThat(projectRow.get("cashflowSource")).isEqualTo("База");
        assertThat(projectRow.get("activeAddendumId")).isNull();
    }

    @Test
    void actualModeUsesCorrectionsWhenFullPairExistsAndSelectsLatestEligibleAddendum() {
        ContractFixture fixture = createFixture("addendum");

        createShipmentItem(fixture.shipmentSchedule, LocalDate.of(2026, 2, 1), 500);
        createPaymentItem(fixture.paymentSchedule, LocalDate.of(2026, 2, 1), 200);

        Addendum olderAddendum = createAddendum(fixture.contract, "A-01", LocalDate.of(2026, 2, 5));
        Addendum latestEligibleAddendum = createAddendum(fixture.contract, "A-02", LocalDate.of(2026, 2, 10));
        createAddendum(fixture.contract, "A-03", LocalDate.of(2026, 2, 20));

        createShipmentCorrectionItem(fixture.project, olderAddendum, LocalDate.of(2026, 2, 6), 10);
        createPaymentCorrectionItem(fixture.project, olderAddendum, LocalDate.of(2026, 2, 6), 5);

        createShipmentCorrectionItem(fixture.project, latestEligibleAddendum, LocalDate.of(2026, 2, 12), 70);
        createShipmentCorrectionItem(fixture.project, latestEligibleAddendum, LocalDate.of(2026, 2, 12), 30);
        createPaymentCorrectionItem(fixture.project, latestEligibleAddendum, LocalDate.of(2026, 2, 12), 40);

        BandData customerBand = new BandData("Customer");
        customerBand.addData("customerId", fixture.customer.getId());

        Map<String, Object> params = Map.of(
                "reportDate", LocalDate.of(2026, 2, 15),
                "contractListMode", ContractListMode.ACTUAL_WITH_ADDENDUMS,
                "contract", fixture.contract
        );

        Map<String, Object> contractRow = listOfContracts.contractsDataLoader()
                .loadData(null, customerBand, params)
                .getFirst();

        assertThat(contractRow)
                .containsEntry("addendumNumber", "A-02")
                .containsEntry("addendumEffectiveDate", LocalDate.of(2026, 2, 10));

        BandData contractBand = new BandData("Contract");
        contractBand.addData("contractId", fixture.contract.getId());
        contractBand.addData("selectedAddendumId", contractRow.get("selectedAddendumId"));

        Map<String, Object> projectRow = listOfContracts.projectsDataLoader()
                .loadData(null, contractBand, Map.of(
                        "project", fixture.project,
                        "contractListMode", ContractListMode.ACTUAL_WITH_ADDENDUMS
                ))
                .getFirst();
        assertThat(projectRow.get("cashflowSource")).isEqualTo("Доп. соглашение");
        assertThat(projectRow.get("activeAddendumId")).isEqualTo(contractRow.get("selectedAddendumId"));

        BandData projectBand = new BandData("Project");
        projectBand.addData("projectId", fixture.project.getId());
        projectBand.addData("activeAddendumId", contractRow.get("selectedAddendumId"));

        List<Map<String, Object>> rows = listOfContracts.projectCashflowRecordsDataLoader()
                .loadData(null, projectBand, params);
        List<Map<String, Object>> totals = listOfContracts.projectCashflowTotalsDataLoader()
                .loadData(null, projectBand, params);

        Map<String, Object> row = rows.getFirst();
        assertThat(row)
                .containsEntry("cashflowDate", LocalDate.of(2026, 2, 12))
                .doesNotContainKey("deltaSum");
        assertThat((BigDecimal) row.get("shipmentSum")).isEqualByComparingTo("100");
        assertThat((BigDecimal) row.get("paymentSum")).isEqualByComparingTo("40");

        Map<String, Object> addendumTotalsRow = totals.getFirst();
        assertThat(addendumTotalsRow).doesNotContainKey("finalDelta");
        assertThat((BigDecimal) addendumTotalsRow.get("shipmentTotal")).isEqualByComparingTo("100");
        assertThat((BigDecimal) addendumTotalsRow.get("paymentTotal")).isEqualByComparingTo("40");
    }

    @Test
    void actualModeFallsBackToBaseWhenProjectHasIncompleteCorrectionPair() {
        ContractFixture fixture = createFixture("fallback");

        createShipmentItem(fixture.shipmentSchedule, LocalDate.of(2026, 2, 1), 500);
        createPaymentItem(fixture.paymentSchedule, LocalDate.of(2026, 2, 1), 200);

        Addendum addendum = createAddendum(fixture.contract, "A-04", LocalDate.of(2026, 2, 10));
        createShipmentCorrectionItem(fixture.project, addendum, LocalDate.of(2026, 2, 12), 100);

        BandData customerBand = new BandData("Customer");
        customerBand.addData("customerId", fixture.customer.getId());

        Map<String, Object> params = Map.of(
                "reportDate", LocalDate.of(2026, 2, 15),
                "contractListMode", ContractListMode.ACTUAL_WITH_ADDENDUMS,
                "contract", fixture.contract
        );

        Map<String, Object> contractRow = listOfContracts.contractsDataLoader()
                .loadData(null, customerBand, params)
                .getFirst();
        assertThat(contractRow.get("selectedAddendumId")).isEqualTo(addendum.getId());

        BandData contractBand = new BandData("Contract");
        contractBand.addData("contractId", fixture.contract.getId());
        contractBand.addData("selectedAddendumId", contractRow.get("selectedAddendumId"));

        Map<String, Object> projectRow = listOfContracts.projectsDataLoader()
                .loadData(null, contractBand, Map.of(
                        "project", fixture.project,
                        "contractListMode", ContractListMode.ACTUAL_WITH_ADDENDUMS
                ))
                .getFirst();
        assertThat(projectRow.get("cashflowSource")).isEqualTo("База");
        assertThat(projectRow.get("activeAddendumId")).isNull();

        BandData projectBand = new BandData("Project");
        projectBand.addData("projectId", fixture.project.getId());
        projectBand.addData("activeAddendumId", null);

        List<Map<String, Object>> rows = listOfContracts.projectCashflowRecordsDataLoader()
                .loadData(null, projectBand, params);
        List<Map<String, Object>> totals = listOfContracts.projectCashflowTotalsDataLoader()
                .loadData(null, projectBand, params);

        Map<String, Object> row = rows.getFirst();
        assertThat(row.get("cashflowDate")).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat((BigDecimal) row.get("shipmentSum")).isEqualByComparingTo("500");
        assertThat((BigDecimal) row.get("paymentSum")).isEqualByComparingTo("200");

        Map<String, Object> totalsRow = totals.getFirst();
        assertThat((BigDecimal) totalsRow.get("shipmentTotal")).isEqualByComparingTo("500");
        assertThat((BigDecimal) totalsRow.get("paymentTotal")).isEqualByComparingTo("200");
    }

    private InputParameterDef findInputParameter(String alias) {
        for (InputParameterDef annotation : ListOfContracts.class.getAnnotationsByType(InputParameterDef.class)) {
            if (alias.equals(annotation.alias())) {
                return annotation;
            }
        }
        throw new IllegalStateException("Input parameter not found: " + alias);
    }

    private ContractFixture createFixture(String suffix) {
        Customer customer = dataManager.create(Customer.class);
        customer.setName("Customer-" + suffix + "-" + UUID.randomUUID());
        customer = dataManager.save(customer);
        cleanupEntities.add(customer);

        LineOfBusiness lineOfBusiness = dataManager.create(LineOfBusiness.class);
        lineOfBusiness.setName("LOB-" + suffix + "-" + UUID.randomUUID());
        lineOfBusiness = dataManager.save(lineOfBusiness);
        cleanupEntities.add(lineOfBusiness);

        Contract contract = dataManager.create(Contract.class);
        contract.setCustomer(customer);
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        contract.setInternalID("CTR-" + suffix + "-" + shortId);
        contract.setStartDate(LocalDate.of(2026, 1, 1));
        contract.setEndDate(LocalDate.of(2026, 12, 31));
        contract.setPaymentType(ContractType.DIRECT_CONSUMER);
        contract.setTotalAmount(BigDecimal.valueOf(1000));
        contract = dataManager.save(contract);
        cleanupEntities.add(contract);

        Project project = dataManager.create(Project.class);
        project.setContract(contract);
        project.setLineOfBusiness(lineOfBusiness);
        project.setName("Project-" + suffix + "-" + UUID.randomUUID());
        project = dataManager.save(project);
        cleanupEntities.add(project);

        ShipmentSchedule shipmentSchedule = dataManager.create(ShipmentSchedule.class);
        shipmentSchedule.setProject(project);
        shipmentSchedule = dataManager.save(shipmentSchedule);
        cleanupEntities.add(shipmentSchedule);

        PaymentSchedule paymentSchedule = dataManager.create(PaymentSchedule.class);
        paymentSchedule.setProject(project);
        paymentSchedule = dataManager.save(paymentSchedule);
        cleanupEntities.add(paymentSchedule);

        return new ContractFixture(customer, contract, project, shipmentSchedule, paymentSchedule);
    }

    private Addendum createAddendum(Contract contract, String number, LocalDate effectiveDate) {
        Addendum addendum = dataManager.create(Addendum.class);
        addendum.setContract(contract);
        addendum.setNumber(number);
        addendum.setEffectiveDate(effectiveDate);
        addendum = dataManager.save(addendum);
        cleanupEntities.add(addendum);
        return addendum;
    }

    private void createShipmentItem(ShipmentSchedule schedule, LocalDate itemDate, int amount) {
        ShipmentScheduleItem item = dataManager.create(ShipmentScheduleItem.class);
        item.setSchedule(schedule);
        item.setItemDate(itemDate);
        item.setAmount(BigDecimal.valueOf(amount));
        item = dataManager.save(item);
        cleanupEntities.add(item);
    }

    private void createPaymentItem(PaymentSchedule schedule, LocalDate itemDate, int amount) {
        PaymentScheduleItem item = dataManager.create(PaymentScheduleItem.class);
        item.setSchedule(schedule);
        item.setItemDate(itemDate);
        item.setAmount(BigDecimal.valueOf(amount));
        item = dataManager.save(item);
        cleanupEntities.add(item);
    }

    private void createShipmentCorrectionItem(Project project, Addendum addendum, LocalDate itemDate, int amount) {
        ShipmentScheduleCorrection correction = dataManager.load(ShipmentScheduleCorrection.class)
                .query("select c from ShipmentScheduleCorrection c where c.project = :project and c.addendum = :addendum")
                .parameter("project", project)
                .parameter("addendum", addendum)
                .optional()
                .orElseGet(() -> {
                    ShipmentScheduleCorrection created = dataManager.create(ShipmentScheduleCorrection.class);
                    created.setProject(project);
                    created.setAddendum(addendum);
                    ShipmentScheduleCorrection saved = dataManager.save(created);
                    cleanupEntities.add(saved);
                    return saved;
                });

        ShipmentScheduleCorrectionItem item = dataManager.create(ShipmentScheduleCorrectionItem.class);
        item.setCorrection(correction);
        item.setItemDate(itemDate);
        item.setAmount(BigDecimal.valueOf(amount));
        item = dataManager.save(item);
        cleanupEntities.add(item);
    }

    private void createPaymentCorrectionItem(Project project, Addendum addendum, LocalDate itemDate, int amount) {
        PaymentScheduleCorrection correction = dataManager.load(PaymentScheduleCorrection.class)
                .query("select c from PaymentScheduleCorrection c where c.project = :project and c.addendum = :addendum")
                .parameter("project", project)
                .parameter("addendum", addendum)
                .optional()
                .orElseGet(() -> {
                    PaymentScheduleCorrection created = dataManager.create(PaymentScheduleCorrection.class);
                    created.setProject(project);
                    created.setAddendum(addendum);
                    PaymentScheduleCorrection saved = dataManager.save(created);
                    cleanupEntities.add(saved);
                    return saved;
                });

        PaymentScheduleCorrectionItem item = dataManager.create(PaymentScheduleCorrectionItem.class);
        item.setCorrection(correction);
        item.setItemDate(itemDate);
        item.setAmount(BigDecimal.valueOf(amount));
        item = dataManager.save(item);
        cleanupEntities.add(item);
    }

    private record ContractFixture(
            Customer customer,
            Contract contract,
            Project project,
            ShipmentSchedule shipmentSchedule,
            PaymentSchedule paymentSchedule
    ) {
    }
}
