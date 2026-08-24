package com.hrportal.model;

public enum EmployeeStatus {
    ACTIVE,
    INACTIVE;

    /** DB stores the original sheet spelling/casing: "Active" / "Resign". */
    public String toDbValue() {
        return this == ACTIVE ? "Active" : "Resign";
    }

    public static EmployeeStatus fromDbValue(String dbValue) {
        if (dbValue == null) return null;
        String v = dbValue.trim().toLowerCase();
        if (v.startsWith("active")) return ACTIVE;
        if (v.startsWith("resign")) return INACTIVE;
        return null;
    }
}
