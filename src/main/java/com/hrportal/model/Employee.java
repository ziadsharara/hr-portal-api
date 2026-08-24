package com.hrportal.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Table(name = "employees")
public class Employee {

    @Id
    @Column(name = "company_code")
    private Integer companyCode;   // business key, NOT auto-generated — assigned from source data / manual entry

    @Column(nullable = false)
    private String name;

    @Column(name = "name_ar")
    private String nameAr;

    @Column(nullable = false)
    private String email;

    private String phone;
    private String position;

    @Column(name = "organizational_unit")
    private String organizationalUnit;

    private String supervisor;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String company; // filtered to 'CIC' at import time

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    private String address;

    @Column(name = "id_number")
    private String idNumber;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "social_status")
    private String socialStatus;

    private String gender;
    private String nationality;
    private String insured;

    @Column(name = "medical_insurance")
    private String medicalInsurance;

    @Column(name = "number_of_insurance")
    private String numberOfInsurance;

    private String laptops;

    @Lob
    private String certificates;

    @Column(name = "experience_years")
    private String experienceYears;

    @Lob
    private String education;

    // New fields (2026-08-20) — no source sheet yet, portal-managed.
    @Column(name = "cv_title")
    private String cvTitle;

    private String languages;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
