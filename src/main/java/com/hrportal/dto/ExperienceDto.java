package com.hrportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ExperienceDto {
    private Long id;
    private Integer companyCode;
    private String project;
    private String projectType;
    private String resourceName;
    private String module;
    private String role;
    private String scope;
    private String industry;
    private String country;
    private LocalDate startDate;
    private String duration;
}
