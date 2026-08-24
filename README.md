# hr-portal-api

Backend API for the HR CV Portal — employee/experience CRUD and PowerPoint
CV generation over a MySQL database. This is one half of a two-repo
system; the Vue SPA that consumes this API lives in the sibling repo
[`hr-portal-frontend`](https://github.com/ziadsharara/hr-portal-frontend),
normally checked out next to this repo as `../frontend`.

## ⚠️ No authentication — read this before deploying anywhere reachable

**This API has no authentication or authorization of any kind.** Every
endpoint — including bulk Excel import and full CV export — is open to
whatever can reach it over the network. If you deploy this, the network
boundary (e.g. an ALB security group restricted to a known CIDR — see
[`DEPLOYMENT.md`](DEPLOYMENT.md)) is the *only* thing standing between
the open internet and the full HR dataset. Do not expose this publicly
until real authentication is added.

## Tech stack

- Java 17, Spring Boot 3.3.4 (`spring-boot-starter-web`, `-data-jpa`,
  `-validation`, `-actuator`)
- MySQL via `mysql-connector-j` (schema is hand-managed SQL, not
  Hibernate DDL — `spring.jpa.hibernate.ddl-auto=none`)
- Apache POI (`poi-ooxml` 5.3.0) for both the `.xlsx` importers and the
  `.pptx` CV generator
- Lombok
- Build: Maven (`pom.xml`)

## Running locally

### Standalone (`mvn spring-boot:run`, `dev` profile)

Requires a MySQL instance running on `localhost:3306` with a
`hr_portal_dev` database and the schema already applied. Defaults to
username/password `root`/`root`; override with `DEV_DB_USERNAME` /
`DEV_DB_PASSWORD` env vars if your local MySQL differs. See
`src/main/resources/application-dev.properties`.

```bash
mvn spring-boot:run
```

### Docker Compose (full stack — MySQL + backend + frontend)

Requires the sibling `hr-portal-frontend` repo checked out at `../frontend`.

```bash
cp .env.compose.example .env.compose   # fill in local values — never commit this file
docker compose --env-file .env.compose up --build
```

- Backend: http://localhost:8080/api
- Backend health: http://localhost:8080/api/actuator/health

Under Compose the backend runs with the `prod` Spring profile (fully
env-var-driven — see below), not `dev`. Full explanation in
[`DEPLOYMENT.md`](DEPLOYMENT.md).

## What's implemented

- **Employee CRUD** — paged/filterable list (search, status, position,
  organizational unit), get by company code, create, update, status
  patch (`EmployeeController`)
- **Employee Excel import** — `POST /employees/import`, create-only
  bulk import from `.xlsx` (`EmployeeExcelParser`)
- **Experience CRUD** — list/create/update/delete, scoped under an
  employee (`ExperienceController`)
- **Experience Excel upload** — both per-employee
  (`POST /employees/{companyCode}/experiences/upload`) and a global
  upload that matches rows to employees by an Employee Code column
  (`POST /experiences/bulk-upload`)
- **CV export (single)** — `GET /employees/{companyCode}/cv` generates
  one employee's CV as a `.pptx` from a token-templated master deck
  (`CvGeneratorService`)
- **CV export (bulk, async job)** — `POST /employees/cv/jobs` starts a
  background job over a list of company codes; poll
  `GET /employees/cv/jobs/{jobId}` for status and
  `GET /employees/cv/jobs/{jobId}/download` for the resulting zip
  (`CvExportJobService`)

## What's NOT implemented

- **Authentication/authorization** (see warning above)
- API request/response validation exists (`jakarta.validation`), but
  there's no rate limiting, audit logging, or soft-delete
- No OpenAPI/Swagger UI — see below for how to find the real contract

## API base path & contract

All routes are mounted under `/api` (`server.servlet.context-path`,
`application.properties`) — e.g. the employee list is
`GET /api/employees`. There is no generated OpenAPI/Swagger doc in this
repo yet; the controllers under
[`src/main/java/com/hrportal/controller/`](src/main/java/com/hrportal/controller/)
are the source of truth for the current endpoint contract.

## Environment variables

| Variable | Used by | Notes |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | prod/Docker/ECS | set to `prod` |
| `DB_URL` | `prod` profile | e.g. `jdbc:mysql://host:3306/db?useSSL=false&serverTimezone=UTC` |
| `DB_USERNAME` | `prod` profile | |
| `DB_PASSWORD` | `prod` profile | |
| `DEV_DB_USERNAME` / `DEV_DB_PASSWORD` | `dev` profile (optional) | overrides the `root`/`root` local default |

For the full local Docker Compose stack (`MYSQL_ROOT_PASSWORD`,
`MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_PORT`,
`BACKEND_PORT`, `FRONTEND_PORT`, `VITE_API_BASE_URL`), see
[`.env.compose.example`](.env.compose.example).

## Deployment

Full CI/CD and AWS (ECS/RDS/Terraform) story lives in
[`DEPLOYMENT.md`](DEPLOYMENT.md) — start there, including the
no-authentication warning and the required one-time AWS setup.
