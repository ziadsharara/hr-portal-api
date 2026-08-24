package com.hrportal.dto;

import com.hrportal.model.EmployeeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeSummaryDto {
    private Integer companyCode;
    private String name;
    private String nameAr;
    private String email;
    private String position;
    private String organizationalUnit;
    private EmployeeStatus status;
    private String company;
    private LocalDateTime updatedAt;
}
