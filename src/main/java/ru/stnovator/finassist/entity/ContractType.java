package ru.stnovator.finassist.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

import org.springframework.lang.Nullable;


public enum ContractType implements EnumClass<String> {

    DIRECT_CONSUMER("A"),
    FACTORING_CONSUMER("C"),
    GENERAL_CONTRACTOR("B");

    private final String id;

    ContractType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    @Nullable
    public static ContractType fromId(String id) {
        for (ContractType at : ContractType.values()) {
            if (at.getId().equals(id)) {
                return at;
            }
        }
        return null;
    }
}