package ru.stnovator.finassist.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface ScheduleItem {
    LocalDate getItemDate();

    BigDecimal getAmount();
}
