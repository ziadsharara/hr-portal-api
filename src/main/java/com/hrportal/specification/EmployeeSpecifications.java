package com.hrportal.specification;

import com.hrportal.model.Employee;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class EmployeeSpecifications {

    public static Specification<Employee> filter(String search, String statusDbValue,
                                                   String position, String organizationalUnit) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("nameAr")), like)
                ));
            }
            if (statusDbValue != null && !statusDbValue.isBlank()) {
                predicates.add(cb.equal(root.get("status"), statusDbValue));
            }
            if (position != null && !position.isBlank()) {
                predicates.add(cb.equal(root.get("position"), position));
            }
            if (organizationalUnit != null && !organizationalUnit.isBlank()) {
                predicates.add(cb.equal(root.get("organizationalUnit"), organizationalUnit));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
