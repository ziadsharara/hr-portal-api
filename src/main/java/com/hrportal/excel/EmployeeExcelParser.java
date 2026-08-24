package com.hrportal.excel;

import org.apache.poi.ss.usermodel.Cell;
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
 * Parses the employee database Excel sheet (HR's "Database CIC 2025"-style
 * workbook — see requirements doc for the full column contract). Applies the
 * row-level filtering rules confirmed against real company data:
 * Company must trim to exactly "CIC", System-placeholder rows (yellow-filled
 * Name cell) are skipped, and non-breaking-space-only cells count as blank.
 */
@Component
public class EmployeeExcelParser {

    private static final List<String> HEADER_MARKERS = List.of("Code", "Name", "Company", "Statues");

    private static final Map<String, List<String>> HEADER_ALIASES = Map.ofEntries(
            Map.entry("code", List.of("Code", "Company Code", "Employee Code")),
            Map.entry("name", List.of("Name")),
            Map.entry("nameAr", List.of("Name2", "Name Ar", "Arabic Name")),
            Map.entry("status", List.of("Statues", "Status")),
            Map.entry("company", List.of("Company")),
            Map.entry("position", List.of("Position")),
            Map.entry("organizationalUnit", List.of("Organizational Unit")),
            Map.entry("supervisor", List.of("Supervisor")),
            Map.entry("start", List.of("start", "Start Date")),
            Map.entry("end", List.of("End", "End Date")),
            Map.entry("address", List.of("Address")),
            Map.entry("idNumber", List.of("ID Number", "ID Number ")),
            Map.entry("dob", List.of("Date of Birth")),
            Map.entry("socialStatus", List.of("Social status", "Social Status")),
            Map.entry("gender", List.of("Gender")),
            Map.entry("nationality", List.of("Nationality")),
            Map.entry("phone", List.of("Phone")),
            Map.entry("email", List.of("E-mail", "Email")),
            Map.entry("insured", List.of("insured", "Insured")),
            Map.entry("medicalInsurance", List.of("medical insurance", "Medical Insurance")),
            Map.entry("numberOfInsurance", List.of("Number of insurance")),
            Map.entry("certificates", List.of("Certificates")),
            Map.entry("laptops", List.of("laptops", "laptops "))
    );

    public EmployeeExcelParseResult parse(MultipartFile file) {
        try (InputStream in = file.getInputStream(); Workbook workbook = WorkbookFactory.create(in)) {
            List<ParsedEmployeeRow> rows = new ArrayList<>();
            int skippedNonCic = 0;
            int skippedSystem = 0;
            int skippedInvalid = 0;
            boolean foundAnyMatchingSheet = false;

            // A workbook may contain more than one sheet with these columns
            // (e.g. one sheet per source company) — read every matching
            // sheet rather than guessing which one is "the" employee sheet.
            // Rows are filtered to Company == CIC below regardless.
            for (Sheet sheet : workbook) {
                int headerRowIdx = ExcelUtil.findHeaderRow(sheet, 15, HEADER_MARKERS);
                if (headerRowIdx < 0) continue;
                foundAnyMatchingSheet = true;

                Row headerRow = sheet.getRow(headerRowIdx);
                Map<String, Integer> cols = ExcelUtil.resolveHeaders(headerRow, HEADER_ALIASES);

                EmployeeExcelParseResult sheetResult = parseSheet(sheet, headerRowIdx, cols);
                rows.addAll(sheetResult.rows());
                skippedNonCic += sheetResult.skippedNonCicCount();
                skippedSystem += sheetResult.skippedSystemRowCount();
                skippedInvalid += sheetResult.skippedInvalidCount();
            }

            if (!foundAnyMatchingSheet) {
                throw new IllegalArgumentException(
                        "Could not find the employee data columns (Code, Name, Company, Statues) in this file.");
            }

            return new EmployeeExcelParseResult(rows, skippedNonCic, skippedSystem, skippedInvalid);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read this file — make sure it is a valid .xlsx file.", e);
        }
    }

    private EmployeeExcelParseResult parseSheet(Sheet sheet, int headerRowIdx, Map<String, Integer> cols) {
        List<ParsedEmployeeRow> rows = new ArrayList<>();
        int skippedNonCic = 0;
        int skippedSystem = 0;
        int skippedInvalid = 0;

        for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (ExcelUtil.isRowEmpty(row)) continue;

                // Checked before the Company filter: real System/placeholder
                // rows carry Company="System" (not "CIC"), so if the Company
                // check ran first every one of them would be counted as
                // "not CIC" and this rule would never actually fire.
                Cell nameCell = ExcelUtil.cellAt(row, cols.get("name"));
                if (ExcelUtil.isYellowFill(nameCell)) {
                    skippedSystem++;
                    continue;
                }

                String company = ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("company")));
                if (company == null || !"CIC".equalsIgnoreCase(company)) {
                    skippedNonCic++;
                    continue;
                }

                Integer code = ExcelUtil.cellInt(ExcelUtil.cellAt(row, cols.get("code")));
                String name = ExcelUtil.cellString(nameCell);
                if (code == null || name == null || name.isBlank()) {
                    skippedInvalid++;
                    continue;
                }

                LocalDate startDate = ExcelUtil.cellDate(ExcelUtil.cellAt(row, cols.get("start")));

                rows.add(new ParsedEmployeeRow(
                        r + 1,
                        code,
                        name,
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("nameAr")))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("status")))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("position")))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("organizationalUnit")))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("supervisor")))),
                        startDate,
                        ExcelUtil.cellDate(ExcelUtil.cellAt(row, cols.get("end"))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("address")))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("idNumber")))),
                        ExcelUtil.cellDate(ExcelUtil.cellAt(row, cols.get("dob"))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("socialStatus")))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("gender")))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("nationality")))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("phone")))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("email")))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("insured")))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("medicalInsurance")))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("numberOfInsurance")))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("certificates")))),
                        blankToNull(ExcelUtil.cellString(ExcelUtil.cellAt(row, cols.get("laptops"))))
                ));
        }

        return new EmployeeExcelParseResult(rows, skippedNonCic, skippedSystem, skippedInvalid);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
