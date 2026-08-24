package com.hrportal.service;

import com.hrportal.dto.ApiErrorResponse;
import com.hrportal.dto.ExperienceDto;
import com.hrportal.dto.ExperienceUploadResponse;
import com.hrportal.dto.ExperienceUploadResponse.UnmatchedRow;
import com.hrportal.dto.ExperienceUpsertRequest;
import com.hrportal.excel.ExperienceExcelParser;
import com.hrportal.excel.ParsedExperienceRow;
import com.hrportal.model.Employee;
import com.hrportal.model.Experience;
import com.hrportal.mapper.ExperienceMapper;
import com.hrportal.repository.EmployeeRepository;
import com.hrportal.repository.ExperienceRepository;
import lombok.RequiredArgsConstructor;
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
public class ExperienceService {

    private final ExperienceRepository experienceRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeService employeeService;
    private final ExperienceExcelParser experienceExcelParser;


    @Transactional(readOnly = true)
    public ResponseEntity<List<ExperienceDto>> getExperiencesList(Integer companyCode) {

        List<Experience> experienceList = experienceRepository.findByEmployee_CompanyCodeOrderByStartDateDesc(companyCode);
        List<ExperienceDto> response = experienceList.stream().map(ExperienceMapper::toDto).toList();
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @Transactional
    public ResponseEntity<?> createExperience(Integer companyCode, ExperienceUpsertRequest request) {
        Optional<Employee> employee = employeeRepository.findById(companyCode);
        if (experienceRepository.existsByEmployee_CompanyCodeAndProjectAndRoleAndStartDate(companyCode, request.getProject(), request.getRole(), request.getStartDate())) {
            return new ResponseEntity<>(
                    new ApiErrorResponse(Instant.now(), "An experience with the same project, role, and start date already exists for this employee"),
                    HttpStatus.BAD_REQUEST);
        };


        Experience experience = new Experience();
        ExperienceMapper.applyUpsert(experience, request, employee.get());
        ExperienceDto response = ExperienceMapper.toDto(experienceRepository.save(experience));
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @Transactional
    public ResponseEntity<?> updateExperience(Integer companyCode, Long experienceId, ExperienceUpsertRequest request) {
        Optional<Employee> employee = employeeRepository.findById(companyCode);
        Optional<Experience> experience = experienceRepository.findById(experienceId);

        if (experience.get().getEmployee() == null || !experience.get().getEmployee().getCompanyCode().equals(companyCode)) {
            return new ResponseEntity<>(
                    new ApiErrorResponse(Instant.now(), "Experience " + experienceId + " does not belong to employee " + companyCode),
                    HttpStatus.BAD_REQUEST
                    );
        }


        ExperienceMapper.applyUpsert(experience.get(), request, employee.get());
        ExperienceDto response = ExperienceMapper.toDto(experienceRepository.save(experience.get()));
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @Transactional
    public void deleteExperience(Integer companyCode, Long experienceId) {
        Optional<Experience> experience = experienceRepository.findById(experienceId);
        experienceRepository.delete(experience.get());
    }

    // Per-employee Excel upload — same file format as bulkUpload, but rows
    // for any OTHER employee code are reported as unmatched (reason:
    // wrong employee), never silently applied to this profile.
    @Transactional
    public ResponseEntity<?> uploadExperiencesForEmployee(Integer companyCode, MultipartFile file) {
        if (!employeeRepository.existsById(companyCode)) {
            return new ResponseEntity<>(
                    new ApiErrorResponse(Instant.now(), "Employee with company code " + companyCode + " does not exist"),
                    HttpStatus.BAD_REQUEST);
        }
        List<ParsedExperienceRow> parsedRows = experienceExcelParser.parse(file);
        return new ResponseEntity<>(processExperienceRows(parsedRows, companyCode), HttpStatus.OK);
    }

    // Global projects-sheet upload — matches every row to an employee by
    // its Employee Code column (never by name; name matching was tried
    // earlier in this project and proved unreliable).
    @Transactional
    public ResponseEntity<?> bulkUploadExperiences(MultipartFile file) {
        List<ParsedExperienceRow> parsedRows = experienceExcelParser.parse(file);
        return new ResponseEntity<>(processExperienceRows(parsedRows, null), HttpStatus.OK);
    }

    // restrictToCompanyCode is non-null only for the per-employee upload,
    // where rows for any other employee code are unmatched rather than applied.
    private ExperienceUploadResponse processExperienceRows(List<ParsedExperienceRow> parsedRows, Integer restrictToCompanyCode) {
        int added = 0;
        int skippedDuplicate = 0;
        List<UnmatchedRow> unmatched = new ArrayList<>();

        for (ParsedExperienceRow row : parsedRows) {
            if (row.employeeCode() == null) {
                String reason = (row.employeeCodeRaw() == null || row.employeeCodeRaw().isBlank())
                        ? "Missing employee code"
                        : "Invalid employee code: \"" + row.employeeCodeRaw() + "\"";
                unmatched.add(new UnmatchedRow(row.rowNumber(), row.employeeCodeRaw(), row.resourceName(), reason));
                continue;
            }

            if (restrictToCompanyCode != null && !row.employeeCode().equals(restrictToCompanyCode)) {
                unmatched.add(new UnmatchedRow(row.rowNumber(), row.employeeCodeRaw(), row.resourceName(),
                        "Row belongs to employee code " + row.employeeCode() + ", not " + restrictToCompanyCode + " — ignored"));
                continue;
            }

            if (row.project() == null || row.project().isBlank()) {
                unmatched.add(new UnmatchedRow(row.rowNumber(), row.employeeCodeRaw(), row.resourceName(), "Missing project name"));
                continue;
            }
            if (row.role() == null || row.role().isBlank()) {
                unmatched.add(new UnmatchedRow(row.rowNumber(), row.employeeCodeRaw(), row.resourceName(), "Missing role"));
                continue;
            }
            if (row.startDate() == null) {
                unmatched.add(new UnmatchedRow(row.rowNumber(), row.employeeCodeRaw(), row.resourceName(), "Missing or invalid start date"));
                continue;
            }

            Optional<Employee> employeeOpt = employeeRepository.findById(row.employeeCode());
            if (employeeOpt.isEmpty()) {
                unmatched.add(new UnmatchedRow(row.rowNumber(), row.employeeCodeRaw(), row.resourceName(),
                        "No employee found with code " + row.employeeCode()));
                continue;
            }
            Employee employee = employeeOpt.get();

            boolean isDuplicate = experienceRepository.existsByEmployee_CompanyCodeAndProjectAndRoleAndStartDate(
                    employee.getCompanyCode(), row.project(), row.role(), row.startDate());
            if (isDuplicate) {
                skippedDuplicate++;
                continue;
            }

            ExperienceUpsertRequest request = ExperienceUpsertRequest.builder()
                    .project(row.project())
                    .projectType(row.projectType())
                    .module(row.module())
                    .role(row.role())
                    .scope(row.scope())
                    .industry(row.industry())
                    .country(row.country())
                    .startDate(row.startDate())
                    .duration(row.duration())
                    .build();

            Experience experience = new Experience();
            ExperienceMapper.applyUpsert(experience, request, employee);
            experienceRepository.save(experience);
            added++;
        }

        String message = added == 0
                ? "No new experiences were added from this file."
                : "Added " + added + " new experience" + (added == 1 ? "" : "s") + ".";

        return new ExperienceUploadResponse(added, skippedDuplicate, unmatched.size(), unmatched, message);
    }
}
