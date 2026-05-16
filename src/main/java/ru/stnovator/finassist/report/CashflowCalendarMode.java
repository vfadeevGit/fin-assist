package ru.stnovator.finassist.report;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

public enum CashflowCalendarMode implements EnumClass<String> {
    BASE("BASE"),
    ACTUAL_WITH_ADDENDUMS("ACTUAL_WITH_ADDENDUMS");

    private final String id;

    CashflowCalendarMode(String id) {
        this.id = id;
    }

    @NonNull
    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public static CashflowCalendarMode fromId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (CashflowCalendarMode value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }
}
