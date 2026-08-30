package com.hrportal.controller;

import com.hrportal.cv.CvGeneratorService;
import com.hrportal.model.Employee;
import com.hrportal.model.Experience;
import com.hrportal.dto.JobResponse;
import com.hrportal.dto.JobStatusResponse;
import com.hrportal.repository.EmployeeRepository;
import com.hrportal.repository.ExperienceRepository;
import com.hrportal.service.CvExportJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;


@RestController
@RequiredArgsConstructor
@RequestMapping("")
@CrossOrigin("*")
public class CvController {

    private static final MediaType PPTX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    private final EmployeeRepository employeeRepository;
    private final ExperienceRepository experienceRepository;
    private final CvGeneratorService cvGeneratorService;
    private final CvExportJobService cvExportJobService;


    @GetMapping("/employees/{companyCode}/cv")
    public ResponseEntity<ByteArrayResource> exportOne(@PathVariable Integer companyCode) {
        Optional<Employee> employee = employeeRepository.findById(companyCode);

        List<Experience> experiences =
                experienceRepository.findByEmployee_CompanyCodeOrderByStartDateDesc(companyCode);

        byte[] pptx = cvGeneratorService.generate(employee.get(), experiences);
        String filename = safeFilename(employee.get().getName()) + "-" + employee.get().getCompanyCode() + "-CV.pptx";

        return ResponseEntity.ok()
                .contentType(PPTX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new ByteArrayResource(pptx));
    }

    public record BulkExportRequest(List<Integer> companyCodes) {}

    @PostMapping("/employees/cv/jobs")
    public ResponseEntity<JobResponse> startExportJob(@RequestBody BulkExportRequest request) {
        if (request.companyCodes() == null || request.companyCodes().isEmpty()) {
            throw new IllegalArgumentException("companyCodes must not be empty");
        }
        String jobId = cvExportJobService.createJob(request.companyCodes());
        cvExportJobService.processJob(jobId, request.companyCodes());
        return ResponseEntity.ok(new JobResponse(jobId, "PROCESSING", request.companyCodes().size()));
    }

    @GetMapping("/employees/cv/jobs/{jobId}")
    public ResponseEntity<JobStatusResponse> getJobStatus(@PathVariable String jobId) {
        JobStatusResponse status = cvExportJobService.getStatus(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @GetMapping("/employees/cv/jobs/{jobId}/download")
    public ResponseEntity<ByteArrayResource> downloadJobResult(@PathVariable String jobId) {
        byte[] zipData = cvExportJobService.getResult(jobId);
        if (zipData == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cv-export-" + jobId + ".zip\"")
                .body(new ByteArrayResource(zipData));
    }

    private String safeFilename(String name) {
        return (name == null ? "employee" : name).replaceAll("[^a-zA-Z0-9-_ ]", "").trim();
    }
}
