package ru.stnovator.finassist.service;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ContractTerms(BigDecimal totalAmount, LocalDate endDate) {
}
