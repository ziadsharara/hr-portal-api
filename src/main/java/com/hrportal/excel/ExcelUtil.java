package com.hrportal.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Color;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFColor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helpers for reading HR's Excel workbooks. The source files are
 * hand-maintained by HR (not generated), so cells routinely mix types
 * (a date typed as text, a code stored as a formatted number, a "blank"
 * cell that is actually a non-breaking space) — every reader here is
 * built to tolerate that rather than throw.
 */
final class ExcelUtil {

    // Yellow fill HR uses to mark placeholder/System rows in the employee sheet.
    static final String YELLOW_ARGB = "FFFFFF00";

    private ExcelUtil() {
    }

    /**
     * Scans the first {@code maxRowsToScan} rows of a sheet for the header
     * row: the first row containing every string in {@code requiredMarkers}
     * (case-insensitive, trimmed) among its cell values. Returns the 0-based
     * row index, or -1 if no such row is found.
     */
    static int findHeaderRow(org.apache.poi.ss.usermodel.Sheet sheet, int maxRowsToScan, List<String> requiredMarkers) {
        int limit = Math.min(maxRowsToScan, sheet.getLastRowNum() + 1);
        for (int r = 0; r <= limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            java.util.Set<String> normalizedCells = new java.util.HashSet<>();
            for (Cell cell : row) {
                String v = cellString(cell);
                if (v != null && !v.isBlank()) {
                    normalizedCells.add(normalizeHeader(v));
                }
            }
            boolean hasAll = requiredMarkers.stream()
                    .map(ExcelUtil::normalizeHeader)
                    .allMatch(normalizedCells::contains);
            if (hasAll) {
                return r;
            }
        }
        return -1;
    }

    /**
     * Builds canonical-field -> column-index map from a header row, given a
     * canonical-field -> accepted-header-aliases table. The first alias found
     * in the header row wins.
     */
    static Map<String, Integer> resolveHeaders(Row headerRow, Map<String, List<String>> canonicalToAliases) {
        Map<String, Integer> normalizedHeaderToCol = new LinkedHashMap<>();
        for (Cell cell : headerRow) {
            String v = cellString(cell);
            if (v != null && !v.isBlank()) {
                normalizedHeaderToCol.putIfAbsent(normalizeHeader(v), cell.getColumnIndex());
            }
        }

        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : canonicalToAliases.entrySet()) {
            for (String alias : entry.getValue()) {
                Integer col = normalizedHeaderToCol.get(normalizeHeader(alias));
                if (col != null) {
                    result.put(entry.getKey(), col);
                    break;
                }
            }
        }
        return result;
    }

    static String normalizeHeader(String s) {
        return s.replace(' ', ' ').trim().replaceAll("\\s+", " ").toLowerCase();
    }

    static Cell cellAt(Row row, Integer colIndex) {
        if (row == null || colIndex == null) return null;
        return row.getCell(colIndex);
    }

    /** True if the row has no meaningful data in any cell (fully blank spreadsheet row). */
    static boolean isRowEmpty(Row row) {
        if (row == null) return true;
        for (Cell cell : row) {
            if (!isBlank(cell)) return false;
        }
        return true;
    }

    /** Treats null, empty string, and non-breaking-space-only cells as blank. */
    static boolean isBlank(Cell cell) {
        String s = cellString(cell);
        return s == null || s.isBlank();
    }

    /**
     * String value of a cell regardless of its underlying Excel type.
     * Non-breaking spaces are normalized to regular spaces and trimmed, so a
     * " "-only cell reads as blank ("").
     */
    static String cellString(Cell cell) {
        if (cell == null) return null;
        CellType type = cell.getCellType();
        String raw;
        switch (type) {
            case STRING -> raw = cell.getStringCellValue();
            case BLANK -> raw = "";
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    raw = cell.getLocalDateTimeCellValue().toLocalDate().toString();
                } else {
                    double d = cell.getNumericCellValue();
                    raw = (d == Math.floor(d) && !Double.isInfinite(d))
                            ? String.valueOf((long) d)
                            : String.valueOf(d);
                }
            }
            case BOOLEAN -> raw = String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> raw = cell.getCellFormula();
            default -> raw = "";
        }
        return raw.replace(' ', ' ').trim();
    }

    /** Parses an integer identifier (company code) from a cell, or null if blank/unparseable. */
    static Integer cellInt(Cell cell) {
        String s = cellString(cell);
        if (s == null || s.isBlank()) return null;
        try {
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a date cell. Handles: real Excel date cells (any type), a
     * numeric Excel date serial stored without date formatting, and text
     * dates in d/M/yyyy form (the format HR's sheets use). Returns null if
     * blank or unparseable.
     */
    static LocalDate cellDate(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            if (DateUtil.isCellDateFormatted(cell)) {
                LocalDateTime ldt = cell.getLocalDateTimeCellValue();
                return ldt.toLocalDate();
            }
            // Plain number that is nonetheless a plausible Excel date serial
            // (HR sometimes pastes dates without carrying the date format).
            if (d > 1000 && d < 80000) {
                return DateUtil.getLocalDateTime(d).toLocalDate();
            }
            return null;
        }

        String s = cellString(cell);
        if (s == null || s.isBlank()) return null;
        String[] parts = s.split("[/\\-.]");
        if (parts.length != 3) return null;
        try {
            int day = Integer.parseInt(parts[0].trim());
            int month = Integer.parseInt(parts[1].trim());
            int year = Integer.parseInt(parts[2].trim());
            if (year < 100) year += 2000;
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    /** True if the cell's fill foreground color matches HR's yellow ("System row") marker. */
    static boolean isYellowFill(Cell cell) {
        if (cell == null || cell.getCellStyle() == null) return false;
        Color color = cell.getCellStyle().getFillForegroundColorColor();
        if (color instanceof XSSFColor xssfColor) {
            String argb = xssfColor.getARGBHex();
            return YELLOW_ARGB.equalsIgnoreCase(argb);
        }
        return false;
    }
}
