package ru.stnovator.finassist.ai;

import io.jmix.core.Metadata;
import org.springframework.stereotype.Component;
import ru.stnovator.finassist.entity.*;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Explicit opt-in for model-visible business entities; never a substitute for access checks. */
@Component
public class BusinessEntityRegistry {
    private final Map<String, Class<?>> entities;

    public BusinessEntityRegistry(Metadata metadata) {
        entities = java.util.stream.Stream.of(Customer.class, Contract.class, Project.class,
                Addendum.class, LineOfBusiness.class, PaymentSchedule.class, ShipmentSchedule.class,
                PaymentScheduleCorrection.class, ShipmentScheduleCorrection.class,
                PaymentScheduleItem.class, ShipmentScheduleItem.class,
                PaymentScheduleCorrectionItem.class, ShipmentScheduleCorrectionItem.class)
                .collect(Collectors.toUnmodifiableMap(c -> metadata.getClass(c).getName(), c -> c));
    }

    public Optional<Class<?>> find(String entityName) {
        return Optional.ofNullable(entityName == null ? null : entities.get(entityName));
    }
}
