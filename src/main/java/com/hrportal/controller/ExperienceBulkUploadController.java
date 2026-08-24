package com.hrportal.controller;

import com.hrportal.service.ExperienceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// Separate from ExperienceController because this endpoint is NOT scoped to
// an employee (ExperienceController is mounted at
// /employees/{companyCode}/experiences) — this one matches every row to an
// employee by its own Employee Code column instead.
@RestController
@RequiredArgsConstructor
@RequestMapping("/experiences")
@CrossOrigin("*")
public class ExperienceBulkUploadController {

    private final ExperienceService experienceService;

    @PostMapping("/bulk-upload")
    public ResponseEntity<?> bulkUpload(@RequestParam("file") MultipartFile file) {
        return experienceService.bulkUploadExperiences(file);
    }
}
