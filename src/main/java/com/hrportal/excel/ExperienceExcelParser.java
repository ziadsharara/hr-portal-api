package com.hrportal.excel;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the projects (Stakeholders-style) Excel sheet. The sheet is
 * grouped/forward-filled: a project's metadata (Project, Project Type,
 * Scope, Industry, Country, Start Date, Duration) is only written on the
 * first row of the group and left blank on the rows below it, while
 * Resource/Module/Role/Employee Code are written on every assignment row.
 * This parser forward-fills each metadata field independently (per-cell,
 * not per-row), which is robust to a group's first row itself carrying no
 * resource assignment (pure project-metadata row).
 */
@Component
public class ExperienceExcelParser {

    private static final List<String> HEADER_MARKERS = List.of("Project", "Resource", "Role");

    private static final Map<String, List<String>> HEADER_ALIASES = Map.ofEntries(
            Map.entry("project", List.of("Project")),
            Map.entry("projectType", List.of("Project Type")),
            Map.entry("resource", List.of("Resource")),
            Map.entry("module", List.of("Module")),
            Map.entry("role", List.of("Role")),
            Map.entry("scope", List.of("Scope")),
            Map.entry("industry", List.of("Industry")),
            Map.entry("country", List.of("Country")),
            Map.entry("startDate", List.of("Start Date")),
            Map.entry("duration", List.of("Duration")),
            Map.entry("employeeCode", List.of("Employee Code", "Company Code", "Code"))
    );

    public List<ParsedExperienceRow> parse(MultipartFile file) {
        try (InputStream in = file.getInputStream(); Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = findProjectsSheet(workbook);
            int headerRowIdx = ExcelUtil.findHeaderRow(sheet, 15, HEADER_MARKERS);
            Row headerRow = sheet.getRow(headerRowIdx);
            Map<String, Integer> cols = ExcelUtil.resolveHeaders(headerRow, HEADER_ALIASES);

            if (!cols.containsKey("employeeCode")) {
                throw new IllegalArgumentException(
                        "This file is missing the Employee Code column. Add a column named " +
                        "\"Employee Code\" with each row's employee company code before uploading.");
            }

            List<ParsedExperienceRow> rows = new ArrayList<>();

            String currProject = null, currProjectType = null, currScope = null,
                    currIndustry = null, currCountry = null, currDuration = null;
            LocalDate currStartDate = null;

            for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (ExcelUtil.isRowEmpty(row)) continue;

                String project = ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("project")));
                if (project != null && !project.isBlank()) currProject = project;

                String projectType = ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("projectType")));
                if (projectType != null && !projectType.isBlank()) currProjectType = projectType;

                String scope = ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("scope")));
                if (scope != null && !scope.isBlank()) currScope = scope;

                String industry = ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("industry")));
                if (industry != null && !industry.isBlank()) currIndustry = industry;

                String country = ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("country")));
                if (country != null && !country.isBlank()) currCountry = country;

                String duration = ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("duration")));
                if (duration != null && !duration.isBlank()) currDuration = duration;

                LocalDate startDate = ExcelUtil.cellDate(ExcelUtil.cellAt(row, cols.get("startDate")));
                if (startDate != null) currStartDate = startDate;

                String resource = ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("resource")));
                if (resource == null || resource.isBlank()) {
                    // Pure project-metadata row (no employee assigned yet) — nothing to import.
                    continue;
                }

                String module = ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("module")));
                String role = ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("role")));
                String employeeCodeRaw = ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("employeeCode")));
                Integer employeeCode = ExcelUtil.cellInt(ExcelUtil.cellAt(row, cols.get("employeeCode")));

                rows.add(new ParsedExperienceRow(
                        r + 1,
                        employeeCodeRaw,
                        employeeCode,
                        resource,
                        currProject,
                        currProjectType,
                        module,
                        role,
                        currScope,
                        currIndustry,
                        currCountry,
                        currStartDate,
                        currDuration
                ));
            }

            return rows;
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read this file — make sure it is a valid .xlsx file.", e);
        }
    }

    private Sheet findProjectsSheet(Workbook workbook) {
        for (Sheet sheet : workbook) {
            if (ExcelUtil.findHeaderRow(sheet, 15, HEADER_MARKERS) >= 0) {
                return sheet;
            }
        }
        throw new IllegalArgumentException(
                "Could not find the projects data columns (Project, Resource, Role) in this file.");
    }
}
