package com.hrportal.dto;

import com.hrportal.model.EmployeeStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;


// Used for both POST /employees and PUT /employees/{companyCode}.
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class EmployeeRequest {

    // Required only on create; the service ignores this field for updates
    // (the path variable is authoritative there).
    private Integer companyCode;

    @NotBlank
    private String name;

    private String nameAr;

    @NotBlank
    @Email
    private String email;

    private String phone;

    @NotBlank
    private String position;

    private String organizationalUnit;
    private String supervisor;

    @NotNull
    private EmployeeStatus status;

    @NotBlank
    private String company;

    @NotNull
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
}
