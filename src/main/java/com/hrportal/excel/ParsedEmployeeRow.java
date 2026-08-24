package com.hrportal.excel;

import java.time.LocalDate;

/** One employee row from the employee Excel sheet, after column mapping. Values are trimmed strings or null. */
public record ParsedEmployeeRow(
        int rowNumber,
        Integer companyCode,
        String name,
        String nameAr,
        String status,
        String position,
        String organizationalUnit,
        String supervisor,
        LocalDate startDate,
        LocalDate endDate,
        String address,
        String idNumber,
        LocalDate dateOfBirth,
        String socialStatus,
        String gender,
        String nationality,
        String phone,
        String email,
        String insured,
        String medicalInsurance,
        String numberOfInsurance,
        String certificates,
        String laptops
) {
}
