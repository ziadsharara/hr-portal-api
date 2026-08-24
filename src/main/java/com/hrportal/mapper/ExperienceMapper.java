package com.hrportal.mapper;

import com.hrportal.dto.ExperienceDto;
import com.hrportal.dto.ExperienceUpsertRequest;
import com.hrportal.model.Employee;
import com.hrportal.model.Experience;
import org.springframework.stereotype.Component;

@Component
public class ExperienceMapper {

    public static ExperienceDto toDto(Experience experience) {
        return ExperienceDto.builder()
                .id(experience.getId())
                .companyCode(
                        experience.getEmployee() != null
                                ? experience.getEmployee().getCompanyCode()
                                : null
                )
                .project(experience.getProject())
                .projectType(experience.getProjectType())
                .resourceName(experience.getResourceName())
                .module(experience.getModule())
                .role(experience.getRole())
                .scope(experience.getScope())
                .industry(experience.getIndustry())
                .country(experience.getCountry())
                .startDate(experience.getStartDate())
                .duration(experience.getDuration())
                .build();
    }


    public static void applyUpsert(
            Experience experience,
            ExperienceUpsertRequest request,
            Employee employee
    ) {
        experience.setEmployee(employee);
        experience.setResourceName(employee.getName());
        experience.setProject(request.getProject());
        experience.setProjectType(request.getProjectType());
        experience.setModule(request.getModule());
        experience.setRole(request.getRole());
        experience.setScope(request.getScope());
        experience.setIndustry(request.getIndustry());
        experience.setCountry(request.getCountry());
        experience.setStartDate(request.getStartDate());
        experience.setDuration(request.getDuration());
    }
}