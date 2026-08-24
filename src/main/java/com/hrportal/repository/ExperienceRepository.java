package com.hrportal.repository;

import com.hrportal.model.Experience;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ExperienceRepository extends JpaRepository<Experience, Long> {

    List<Experience> findByEmployee_CompanyCodeOrderByStartDateDesc(Integer companyCode);

    boolean existsByEmployee_CompanyCodeAndProjectAndRoleAndStartDate(
            Integer companyCode, String project, String role, LocalDate startDate);
}
