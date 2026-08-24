package com.hrportal.dto;

import java.util.List;

// Response shape shared by both experience-upload endpoints (per-employee
// and global bulk) — same source file format, same outcomes: a row is
// either added, skipped as an exact duplicate of an existing experience, or
// unmatched (bad/missing employee code, wrong employee, or missing required
// fields) and reported in `unmatchedRows` so HR can fix the sheet and re-upload.
public record ExperienceUploadResponse(
        int addedCount,
        int skippedDuplicateCount,
        int unmatchedCount,
        List<UnmatchedRow> unmatchedRows,
        String message
) {
    public record UnmatchedRow(int rowNumber, String employeeCode, String resourceName, String reason) {
    }
}
