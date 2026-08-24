package com.hrportal.mapper;

import com.hrportal.dto.EmployeeDetailDto;
import com.hrportal.dto.EmployeeSummaryDto;
import com.hrportal.dto.EmployeeRequest;
import com.hrportal.model.Employee;
import com.hrportal.model.EmployeeStatus;
import org.springframework.stereotype.Component;

@Component
public class EmployeeMapper {

    public static EmployeeSummaryDto toSummary(Employee employee) {
        return EmployeeSummaryDto.builder()
                .companyCode(employee.getCompanyCode())
                .name(employee.getName())
                .nameAr(employee.getNameAr())
                .email(employee.getEmail())
                .position(employee.getPosition())
                .organizationalUnit(employee.getOrganizationalUnit())
                .status(EmployeeStatus.fromDbValue(employee.getStatus()))
                .company(employee.getCompany())
                .updatedAt(employee.getUpdatedAt())
                .build();
    }

    public static EmployeeDetailDto toDetail(Employee employee) {
        return EmployeeDetailDto.builder()
                .companyCode(employee.getCompanyCode())
                .name(employee.getName())
                .nameAr(employee.getNameAr())
                .email(employee.getEmail())
                .phone(employee.getPhone())
                .position(employee.getPosition())
                .organizationalUnit(employee.getOrganizationalUnit())
                .supervisor(employee.getSupervisor())
                .status(EmployeeStatus.fromDbValue(employee.getStatus()))
                .company(employee.getCompany())
                .startDate(employee.getStartDate())
                .endDate(employee.getEndDate())
                .address(employee.getAddress())
                .idNumber(employee.getIdNumber())
                .dateOfBirth(employee.getDateOfBirth())
                .socialStatus(employee.getSocialStatus())
                .gender(employee.getGender())
                .nationality(employee.getNationality())
                .insured(employee.getInsured())
                .medicalInsurance(employee.getMedicalInsurance())
                .numberOfInsurance(employee.getNumberOfInsurance())
                .laptops(employee.getLaptops())
                .certificates(employee.getCertificates())
                .experienceYears(employee.getExperienceYears())
                .education(employee.getEducation())
                .cvTitle(employee.getCvTitle())
                .languages(employee.getLanguages())
                .createdAt(employee.getCreatedAt())
                .updatedAt(employee.getUpdatedAt())
                .build();
    }

    public static void applyUpsert(Employee employee, EmployeeRequest request) {
        employee.setName(request.getName());
        employee.setNameAr(request.getNameAr());
        employee.setEmail(request.getEmail());
        employee.setPhone(request.getPhone());
        employee.setPosition(request.getPosition());
        employee.setOrganizationalUnit(request.getOrganizationalUnit());
        employee.setSupervisor(request.getSupervisor());
        employee.setStatus(request.getStatus().toDbValue());
        employee.setCompany(request.getCompany());
        employee.setStartDate(request.getStartDate());
        employee.setEndDate(request.getEndDate());
        employee.setAddress(request.getAddress());
        employee.setIdNumber(request.getIdNumber());
        employee.setDateOfBirth(request.getDateOfBirth());
        employee.setSocialStatus(request.getSocialStatus());
        employee.setGender(request.getGender());
        employee.setNationality(request.getNationality());
        employee.setInsured(request.getInsured());
        employee.setMedicalInsurance(request.getMedicalInsurance());
        employee.setNumberOfInsurance(request.getNumberOfInsurance());
        employee.setLaptops(request.getLaptops());
        employee.setCertificates(request.getCertificates());
        employee.setExperienceYears(request.getExperienceYears());
        employee.setEducation(request.getEducation());
        employee.setCvTitle(request.getCvTitle());
        employee.setLanguages(request.getLanguages());
    }
}