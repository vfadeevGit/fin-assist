package ru.stnovator.finassist.service;

import org.springframework.stereotype.Service;
import ru.stnovator.finassist.entity.Addendum;
import ru.stnovator.finassist.entity.PaymentSchedule;
import ru.stnovator.finassist.entity.PaymentScheduleCorrection;
import ru.stnovator.finassist.entity.Project;
import ru.stnovator.finassist.entity.ScheduleItem;
import ru.stnovator.finassist.entity.ShipmentSchedule;
import ru.stnovator.finassist.entity.ShipmentScheduleCorrection;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class ScheduleResolutionService {

    public List<? extends ScheduleItem> resolveShipmentScheduleItems(Project project, LocalDate date) {
        if (project == null) {
            return Collections.emptyList();
        }

        ShipmentScheduleCorrection correction = findShipmentCorrection(project, date);
        if (correction != null && correction.getItems() != null) {
            return correction.getItems();
        }

        ShipmentSchedule schedule = project.getShipmentSchedule();
        return schedule == null || schedule.getItems() == null
                ? Collections.emptyList()
                : schedule.getItems();
    }

    public List<? extends ScheduleItem> resolvePaymentScheduleItems(Project project, LocalDate date) {
        if (project == null) {
            return Collections.emptyList();
        }

        PaymentScheduleCorrection correction = findPaymentCorrection(project, date);
        if (correction != null && correction.getItems() != null) {
            return correction.getItems();
        }

        PaymentSchedule schedule = project.getPaymentSchedule();
        return schedule == null || schedule.getItems() == null
                ? Collections.emptyList()
                : schedule.getItems();
    }

    private ShipmentScheduleCorrection findShipmentCorrection(Project project, LocalDate date) {
        if (date == null || project.getContract() == null || project.getContract().getAddenda() == null) {
            return null;
        }

        return project.getContract().getAddenda().stream()
                .filter(addendum -> isEffectiveOn(addendum, date))
                .map(addendum -> findShipmentCorrection(addendum, project))
                .filter(Objects::nonNull)
                .max(Comparator.comparing(correction -> correction.getAddendum().getEffectiveDate()))
                .orElse(null);
    }

    private PaymentScheduleCorrection findPaymentCorrection(Project project, LocalDate date) {
        if (date == null || project.getContract() == null || project.getContract().getAddenda() == null) {
            return null;
        }

        return project.getContract().getAddenda().stream()
                .filter(addendum -> isEffectiveOn(addendum, date))
                .map(addendum -> findPaymentCorrection(addendum, project))
                .filter(Objects::nonNull)
                .max(Comparator.comparing(correction -> correction.getAddendum().getEffectiveDate()))
                .orElse(null);
    }

    private boolean isEffectiveOn(Addendum addendum, LocalDate date) {
        return addendum != null && addendum.getEffectiveDate() != null
                && !addendum.getEffectiveDate().isAfter(date);
    }

    private ShipmentScheduleCorrection findShipmentCorrection(Addendum addendum, Project project) {
        List<ShipmentScheduleCorrection> corrections = addendum.getShipmentScheduleCorrections();
        if (corrections == null) {
            return null;
        }

        Optional<ShipmentScheduleCorrection> match = corrections.stream()
                .filter(correction -> Objects.equals(correction.getProject(), project))
                .findFirst();
        return match.orElse(null);
    }

    private PaymentScheduleCorrection findPaymentCorrection(Addendum addendum, Project project) {
        List<PaymentScheduleCorrection> corrections = addendum.getPaymentScheduleCorrections();
        if (corrections == null) {
            return null;
        }

        Optional<PaymentScheduleCorrection> match = corrections.stream()
                .filter(correction -> Objects.equals(correction.getProject(), project))
                .findFirst();
        return match.orElse(null);
    }
}
