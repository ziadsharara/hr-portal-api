package com.hrportal.service;

import com.hrportal.dto.*;
import com.hrportal.excel.EmployeeExcelParseResult;
import com.hrportal.excel.EmployeeExcelParser;
import com.hrportal.excel.ParsedEmployeeRow;
import com.hrportal.model.Employee;
import com.hrportal.mapper.EmployeeMapper;
import com.hrportal.model.EmployeeStatus;
import com.hrportal.repository.EmployeeRepository;
import com.hrportal.specification.EmployeeSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeExcelParser employeeExcelParser;

    private static final int MAX_LISTED_SKIPPED_EXISTING = 50;

    @Transactional(readOnly = true)
    public PageResponse<EmployeeSummaryDto> getEmployeeListPaged(String search, EmployeeStatus status, String position,
                                                                 String organizationalUnit, Pageable pageable) {
        String statusDbValue = status != null ? status.toDbValue() : null;

        Specification<Employee> spec =
                EmployeeSpecifications.filter(
                        search,
                        statusDbValue,
                        position,
                        organizationalUnit
                );

        Page<EmployeeSummaryDto> responsePages = employeeRepository.findAll(spec, pageable).map(EmployeeMapper::toSummary);
        return PageResponse.of(responsePages);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<EmployeeDetailDto> getEmployee(Integer companyCode) {
        Optional<Employee> employee = employeeRepository.findById(companyCode);
        EmployeeDetailDto response = EmployeeMapper.toDetail(employee.get());

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @Transactional
    public ResponseEntity<?> createEmployee(EmployeeRequest request) {

        if (request.getCompanyCode() == null) {
            return new ResponseEntity<>(
                    new ApiErrorResponse(Instant.now(), "Company code is missing"),
                    HttpStatus.BAD_REQUEST);
        }

        if (employeeRepository.existsById(request.getCompanyCode())) {
            return new ResponseEntity<>(
                    new ApiErrorResponse(Instant.now(), "Employee with this company code: "+request.getCompanyCode()+" already exist"),
                    HttpStatus.BAD_REQUEST);
        }

        Employee employee = new Employee();
        employee.setCompanyCode(request.getCompanyCode());
        EmployeeMapper.applyUpsert(employee, request);
        employeeRepository.save(employee);
        EmployeeDetailDto response = EmployeeMapper.toDetail(employee);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @Transactional
    public ResponseEntity<?> updateEmployee(Integer companyCode, EmployeeRequest request) {
        if (request.getCompanyCode() != null && !request.getCompanyCode().equals(companyCode)) {
            return new ResponseEntity<>(
                    new ApiErrorResponse(Instant.now(), "Company code in body does not match path"),
                    HttpStatus.BAD_REQUEST);
        }
        Optional<Employee> employee = employeeRepository.findById(companyCode);
        EmployeeMapper.applyUpsert(employee.get(), request);
        EmployeeDetailDto response = EmployeeMapper.toDetail(employeeRepository.save(employee.get()));
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @Transactional
    public ResponseEntity<EmployeeDetailDto> updateEmployeeStatus(Integer companyCode, EmployeeStatus status) {
        Optional<Employee> employee = employeeRepository.findById(companyCode);
        employee.get().setStatus(status.toDbValue());
        EmployeeDetailDto response = EmployeeMapper.toDetail(employeeRepository.save(employee.get()));
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    // Bulk employee Excel import — CREATE-ONLY. A company_code already in
    // the DB is skipped, never overwritten (the profile edit form is the
    // only way to change an existing employee's data); this is what avoids
    // the same silent-overwrite risk PUT's full-replace semantics have.
    @Transactional
    public ResponseEntity<EmployeeImportResponse> importEmployees(MultipartFile file) {
        EmployeeExcelParseResult parsed = employeeExcelParser.parse(file);

        List<Integer> added = new ArrayList<>();
        List<Integer> skippedExisting = new ArrayList<>();

        for (ParsedEmployeeRow row : parsed.rows()) {
            if (employeeRepository.existsById(row.companyCode())) {
                skippedExisting.add(row.companyCode());
                continue;
            }

            Employee employee = new Employee();
            employee.setCompanyCode(row.companyCode());
            employee.setName(row.name());
            employee.setNameAr(row.nameAr());
            employee.setEmail(row.email());
            employee.setPhone(row.phone());
            employee.setPosition(row.position());
            employee.setOrganizationalUnit(row.organizationalUnit());
            employee.setSupervisor(row.supervisor());
            employee.setStatus(row.status());
            employee.setCompany("CIC");
            employee.setStartDate(row.startDate());
            employee.setEndDate(row.endDate());
            employee.setAddress(row.address());
            employee.setIdNumber(row.idNumber());
            employee.setDateOfBirth(row.dateOfBirth());
            employee.setSocialStatus(row.socialStatus());
            employee.setGender(row.gender());
            employee.setNationality(row.nationality());
            employee.setInsured(row.insured());
            employee.setMedicalInsurance(row.medicalInsurance());
            employee.setNumberOfInsurance(row.numberOfInsurance());
            employee.setCertificates(row.certificates());
            employee.setLaptops(row.laptops());
            employeeRepository.save(employee);
            added.add(row.companyCode());
        }

        String message = added.isEmpty()
                ? "No new employees found in this file."
                : "Added " + added.size() + " new employee" + (added.size() == 1 ? "" : "s") + ".";

        EmployeeImportResponse response = new EmployeeImportResponse(
                added.size(),
                added,
                skippedExisting.size(),
                skippedExisting.size() <= MAX_LISTED_SKIPPED_EXISTING ? skippedExisting : List.of(),
                parsed.skippedNonCicCount(),
                parsed.skippedSystemRowCount(),
                parsed.skippedInvalidCount(),
                message
        );
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

//    /** Used by ExperienceService to attach an experience to its employee. */
//    @Transactional(readOnly = true)
//    public Employee getEntityOrThrow(Integer companyCode) {
//        return findOrThrow(companyCode);
//    }
}
