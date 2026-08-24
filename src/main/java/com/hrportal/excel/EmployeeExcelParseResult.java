package com.hrportal.excel;

import java.util.List;

/**
 * Result of parsing the employee Excel sheet, before touching the database.
 * {@code rows} are the CIC, non-System rows that are candidates for import —
 * whether each one is actually new is decided against the database in the service layer.
 */
public record EmployeeExcelParseResult(
        List<ParsedEmployeeRow> rows,
        int skippedNonCicCount,
        int skippedSystemRowCount,
        int skippedInvalidCount
) {
}
