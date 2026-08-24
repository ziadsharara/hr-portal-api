package com.hrportal.controller;

import com.hrportal.dto.ExperienceDto;
import com.hrportal.dto.ExperienceUpsertRequest;
import com.hrportal.service.ExperienceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/employees/{companyCode}/experiences")
@CrossOrigin("*")
public class ExperienceController {

    private final ExperienceService experienceService;


    @GetMapping
    public ResponseEntity<List<ExperienceDto>> getExperiencesList(@PathVariable Integer companyCode) {
        return experienceService.getExperiencesList(companyCode);
    }

    @PostMapping
    public ResponseEntity<?> createExperience(@PathVariable Integer companyCode,
                                                          @Valid @RequestBody ExperienceUpsertRequest req) {

        return experienceService.createExperience(companyCode, req);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateExperience(@PathVariable Integer companyCode, @PathVariable Long id,
                                          @Valid @RequestBody ExperienceUpsertRequest req) {

        return experienceService.updateExperience(companyCode, id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteExperience(@PathVariable Integer companyCode, @PathVariable Long id) {
        experienceService.deleteExperience(companyCode, id);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Experience deleted successfully");
        return new ResponseEntity(response, HttpStatus.OK);
    }

    // Per-employee Excel upload — see ExperienceService.uploadExperiencesForEmployee.
    // (The global upload, POST /experiences/bulk-upload, lives in
    // ExperienceBulkUploadController since it isn't scoped to an employee.)
    @PostMapping("/upload")
    public ResponseEntity<?> uploadExperiences(@PathVariable Integer companyCode,
                                                @RequestParam("file") MultipartFile file) {
        return experienceService.uploadExperiencesForEmployee(companyCode, file);
    }
}
