package com.hrportal.service;

import com.hrportal.cv.CvGeneratorService;
import com.hrportal.dto.JobStatusResponse;
import com.hrportal.model.Employee;
import com.hrportal.model.Experience;
import com.hrportal.repository.EmployeeRepository;
import com.hrportal.repository.ExperienceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class CvExportJobService {

    private final EmployeeRepository employeeRepository;
    private final ExperienceRepository experienceRepository;
    private final CvGeneratorService cvGeneratorService;

    // In-memory state tracking
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    public static class JobState {
        public String status = "PROCESSING"; // PROCESSING, COMPLETED, FAILED
        public int completed = 0;
        public int total = 0;
        public byte[] zipData = null;
    }

    public String createJob(List<Integer> companyCodes) {
        String jobId = UUID.randomUUID().toString();
        JobState state = new JobState();
        state.total = companyCodes.size();
        jobs.put(jobId, state);
        return jobId;
    }

    @Async
    public void processJob(String jobId, List<Integer> companyCodes) {
        JobState state = jobs.get(jobId);
        if (state == null) return;

        try (ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(zipBytes)) {

            for (Integer companyCode : companyCodes) {
                try {
                    Employee employee = employeeRepository.findById(companyCode).orElse(null);
                    if (employee != null) {
                        List<Experience> experiences = experienceRepository.findByEmployee_CompanyCodeOrderByStartDateDesc(companyCode);
                        byte[] pptx = cvGeneratorService.generate(employee, experiences);

                        String filename = safeFilename(employee.getName()) + "-CV.pptx";
                        zip.putNextEntry(new ZipEntry(filename));
                        zip.write(pptx);
                        zip.closeEntry();
                    }
                } catch (Exception e) {
                    log.error("Failed to generate CV for employee {}", companyCode, e);
                    // continue to next rather than fail the entire batch
                }
                state.completed++;
            }
            zip.finish();
            state.zipData = zipBytes.toByteArray();
            state.status = "COMPLETED";

        } catch (Exception e) {
            log.error("Job {} failed", jobId, e);
            state.status = "FAILED";
        }
    }

    public JobStatusResponse getStatus(String jobId) {
        JobState state = jobs.get(jobId);
        if (state == null) return null;
        return new JobStatusResponse(state.status, state.completed, state.total);
    }

    public byte[] getResult(String jobId) {
        JobState state = jobs.get(jobId);
        if (state == null || !"COMPLETED".equals(state.status)) {
            return null;
        }
        return state.zipData;
    }

    private String safeFilename(String name) {
        return (name == null ? "employee" : name).replaceAll("[^a-zA-Z0-9-_ ]", "").trim();
    }
}
