package com.hrportal.controller;

import com.hrportal.dto.*;
import com.hrportal.model.EmployeeStatus;
import com.hrportal.service.EmployeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/employees")
@CrossOrigin("*")
public class EmployeeController {

    private final EmployeeService employeeService;

    // Get list of employees paged for home screen of dashboard
    @GetMapping
    public PageResponse<EmployeeSummaryDto> getEmployeeListPaged(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) EmployeeStatus status,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String organizationalUnit,
            Pageable pageable) {

        return employeeService.getEmployeeListPaged(search, status, position, organizationalUnit, pageable);
    }

    // Get employee by company code - profile page
    @GetMapping("/{companyCode}")
    public ResponseEntity<EmployeeDetailDto> getEmployee(@PathVariable Integer companyCode) {

        return employeeService.getEmployee(companyCode);
    }

    // Create new employee
    @PostMapping
    public ResponseEntity<?> createEmployee(@Valid @RequestBody EmployeeRequest request) {

        return employeeService.createEmployee(request);
    }

    // Update employee
    @PutMapping("/{companyCode}")
    public ResponseEntity<?> updateEmployee(@PathVariable Integer companyCode, @Valid @RequestBody EmployeeRequest req) {
        return employeeService.updateEmployee(companyCode, req);
    }

    @PatchMapping("/{companyCode}/status")
    public ResponseEntity<?> updateEmployeeStatus(@PathVariable Integer companyCode,
                                                  @Valid @RequestBody EmployeeStatusUpdateRequest req) {
        return employeeService.updateEmployeeStatus(companyCode, req.getStatus());
    }

    // Bulk employee Excel import — create-only
    @PostMapping("/import")
    public ResponseEntity<EmployeeImportResponse> importEmployees(@RequestParam("file") MultipartFile file) {
        return employeeService.importEmployees(file);
    }
}
