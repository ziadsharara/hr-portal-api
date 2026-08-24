-- HR Portal schema.
--
-- READ THIS BEFORE TRUSTING IT IN PRODUCTION.
--
-- application.properties sets `spring.jpa.hibernate.ddl-auto=none` and
-- its comment says the schema is owned by `01_create_schema.sql` /
-- `02_create_dev_schema.sql`. Neither of those files exists anywhere in
-- this repository or in its git history — so a fresh database had no
-- schema at all, and every query would fail on startup.
--
-- This file was RECONSTRUCTED from the JPA entities in
-- src/main/java/com/hrportal/model/ (Employee.java, Experience.java) to
-- unblock deployment. Column names, nullability, and the foreign key
-- are taken directly from the annotations and are reliable. What is NOT
-- reliable, because annotations do not record it:
--
--   * VARCHAR lengths. Every unannotated String is 255 here, which is
--     Hibernate's default, not necessarily what the original schema used.
--   * Index and unique-constraint choices beyond the primary/foreign
--     keys. Only the indexes the query layer visibly needs are declared.
--   * Character set and collation. utf8mb4 / utf8mb4_unicode_ci is
--     assumed, which matters because `name_ar` holds Arabic text.
--
-- If the original DDL is recovered, prefer it over this file and diff
-- the two before replacing.
--
-- Applied by the MySQL container's entrypoint on FIRST BOOT ONLY, from
-- /docker-entrypoint-initdb.d. It will not re-run against a data
-- directory that already exists, so edits here do not migrate a live
-- database — this project has no migration tool (no Flyway, no
-- Liquibase), which is worth adding before the schema ever changes.

SET NAMES utf8mb4;

-- --------------------------------------------------------------------
-- employees
--
-- company_code is a business key assigned from the source HR data, not
-- generated: Employee.java declares @Id with no @GeneratedValue.
-- --------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `employees` (
  `company_code`        INT           NOT NULL,

  `name`                VARCHAR(255)  NOT NULL,
  `name_ar`             VARCHAR(255)      NULL,
  `email`               VARCHAR(255)  NOT NULL,
  `phone`               VARCHAR(255)      NULL,
  `position`            VARCHAR(255)      NULL,
  `organizational_unit` VARCHAR(255)      NULL,
  `supervisor`          VARCHAR(255)      NULL,

  -- Stores the original sheet spelling, 'Active' / 'Resign', not the
  -- ACTIVE/INACTIVE enum constant. See EmployeeStatus.toDbValue().
  `status`              VARCHAR(255)  NOT NULL,

  -- Filtered to 'CIC' at import time.
  `company`             VARCHAR(255)  NOT NULL,

  `start_date`          DATE              NULL,
  `end_date`            DATE              NULL,
  `address`             VARCHAR(255)      NULL,
  `id_number`           VARCHAR(255)      NULL,
  `date_of_birth`       DATE              NULL,
  `social_status`       VARCHAR(255)      NULL,
  `gender`              VARCHAR(255)      NULL,
  `nationality`         VARCHAR(255)      NULL,
  `insured`             VARCHAR(255)      NULL,
  `medical_insurance`   VARCHAR(255)      NULL,
  `number_of_insurance` VARCHAR(255)      NULL,
  `laptops`             VARCHAR(255)      NULL,

  -- @Lob in Employee.java.
  `certificates`        LONGTEXT          NULL,
  `experience_years`    VARCHAR(255)      NULL,
  `education`           LONGTEXT          NULL,

  -- Portal-managed, no source sheet.
  `cv_title`            VARCHAR(255)      NULL,
  `languages`           VARCHAR(255)      NULL,

  -- Both are mapped insertable=false/updatable=false in the entity, so
  -- the database, not Hibernate, has to supply these values.
  `created_at`          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                               ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`company_code`),

  -- EmployeeSpecification filters on these; without indexes every
  -- filtered list scans the table.
  KEY `idx_employees_status`              (`status`),
  KEY `idx_employees_company`             (`company`),
  KEY `idx_employees_position`            (`position`),
  KEY `idx_employees_organizational_unit` (`organizational_unit`),
  KEY `idx_employees_name`                (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------------------
-- experiences
--
-- Experience.employee is @ManyToOne on @JoinColumn(name = "company_code").
-- resource_name keeps the raw external name from the uploaded sheet even
-- after a row has been matched to an employee, which is why it is
-- nullable and separate from the foreign key.
-- --------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `experiences` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,

  -- Nullable: the bulk upload matches rows to employees by an Employee
  -- Code column, and an unmatched row still has to be storable.
  `company_code`  INT               NULL,

  `project`       VARCHAR(255)  NOT NULL,
  `project_type`  VARCHAR(255)      NULL,
  `resource_name` VARCHAR(255)      NULL,
  `module`        VARCHAR(255)      NULL,
  `role`          VARCHAR(255)  NOT NULL,

  -- @Lob in Experience.java.
  `scope`         LONGTEXT          NULL,

  `industry`      VARCHAR(255)      NULL,
  `country`       VARCHAR(255)      NULL,
  `start_date`    DATE          NOT NULL,
  `duration`      VARCHAR(255)      NULL,

  `created_at`    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                         ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`id`),
  KEY `idx_experiences_company_code` (`company_code`),

  -- ON DELETE CASCADE: an employee's experience rows have no meaning
  -- once the employee is gone, and the API exposes no orphan cleanup.
  CONSTRAINT `fk_experiences_employee`
    FOREIGN KEY (`company_code`) REFERENCES `employees` (`company_code`)
    ON DELETE CASCADE
    ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
