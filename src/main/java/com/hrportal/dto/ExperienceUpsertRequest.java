package com.hrportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;


@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ExperienceUpsertRequest {

    @NotBlank
    private String project;

    private String projectType;
    private String module;

    @NotBlank
    private String role;

    private String scope;
    private String industry;
    private String country;

    @NotNull
    private LocalDate startDate;

    private String duration;
}
