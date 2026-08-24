package com.hrportal.dto;

import com.hrportal.model.EmployeeStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class EmployeeStatusUpdateRequest {
    @NotNull
    private EmployeeStatus status;
}
