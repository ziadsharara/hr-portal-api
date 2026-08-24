package com.hrportal.dto;

import java.util.List;

// Response for POST /employees/import. `message` always carries the
// human-readable headline — in particular "No new employees found in this
// file." when addedCount is 0 — so the UI never has to infer the empty
// state from a bare zero next to other counts.
public record EmployeeImportResponse(
        int addedCount,
        List<Integer> addedCompanyCodes,
        int skippedExistingCount,
        // Capped for readability when re-importing a mostly-unchanged sheet;
        // skippedExistingCount is always accurate even when this list is empty.
        List<Integer> skippedExistingCompanyCodes,
        int skippedNonCicCount,
        int skippedSystemRowCount,
        int skippedInvalidCount,
        String message
) {
}
