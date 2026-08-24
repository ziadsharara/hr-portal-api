package com.hrportal.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Table(name = "experiences")
public class Experience {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // TODO
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_code")
    private Employee employee;

    @Column(nullable = false)
    private String project;

    @Column(name = "project_type")
    private String projectType;

    @Column(name = "resource_name")
    private String resourceName; // raw external name, kept even after matching

    private String module;

    @Column(nullable = false)
    private String role;

    @Lob
    private String scope;

    private String industry;
    private String country;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    private String duration;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    @UpdateTimestamp
    private LocalDateTime updatedAt;

}
