package com.hrportal.dto;

import com.hrportal.model.EmployeeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class EmployeeDetailDto {
    private Integer companyCode;
    private String name;
    private String nameAr;
    private String email;
    private String phone;
    private String position;
    private String organizationalUnit;
    private String supervisor;
    private EmployeeStatus status;
    private String company;
    private LocalDate startDate;
    private LocalDate endDate;
    private String address;
    private String idNumber;
    private LocalDate dateOfBirth;
    private String socialStatus;
    private String gender;
    private String nationality;
    private String insured;
    private String medicalInsurance;
    private String numberOfInsurance;
    private String laptops;
    private String certificates;
    private String experienceYears;
    private String education;
    private String cvTitle;
    private String languages;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
