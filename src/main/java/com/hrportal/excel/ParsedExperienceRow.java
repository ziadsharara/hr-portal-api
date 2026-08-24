package com.hrportal.excel;

import java.time.LocalDate;

/**
 * One resource-assignment row from the projects (Stakeholders) Excel sheet,
 * after forward-fill and column mapping. {@code employeeCodeRaw} is kept as
 * the raw sheet text (even when unparseable) so it can be echoed back to HR
 * in the unmatched-rows report.
 */
public record ParsedExperienceRow(
        int rowNumber,
        String employeeCodeRaw,
        Integer employeeCode,
        String resourceName,
        String project,
        String projectType,
        String module,
        String role,
        String scope,
        String industry,
        String country,
        LocalDate startDate,
        String duration
) {
}
