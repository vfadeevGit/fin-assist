package ru.stnovator.finassist.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

import org.springframework.lang.Nullable;


public enum WorkSheetType implements EnumClass<String> {

    PLAN("P"),
    FACT("F");

    private final String id;

    WorkSheetType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    @Nullable
    public static WorkSheetType fromId(String id) {
        for (WorkSheetType at : WorkSheetType.values()) {
            if (at.getId().equals(id)) {
                return at;
            }
        }
        return null;
    }
}