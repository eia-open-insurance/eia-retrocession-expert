# eia-retrocession-expert

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-retrocession-expert
- Type: Spring Boot service
- Deployable: Yes
- Owner: Retrocession
- Runtime: Java 22, Spring Boot, Spring Batch, Quartz
- Data store: eia_retrocession (ent, ref, audit schemas)
- Depends on: eia-contracts, eia-platform/libs

## Purpose

Retrocession persistence, invariants, read models, batch jobs, Quartz schedules, and outbox/inbox. Assumed-book retrocession contracts, retro rules, FS-RI hand-off, and recoverables.

## Implemented slice

- Spring Boot 3.4.0 on Java 22, port `8093`, PostgreSQL database `eia_retrocession`.
- `retrocession` table with `retrocession_number`, `contract_id`, `retro_contract_id`, `status`, `ceded_premium`, `ceded_loss`, `currency`, `version`, `created_at`, and `updated_at`.
- Internal API under `/internal/v1/retrocessions` (list, get, create, status transition).
- Enforces ASSUMED-only book, non-negative ceded amounts, three-letter currency, the retro-case lifecycle from SPEC 11.10.3, and optimistic versioning.
- JUnit controller and service tests.

## Local run

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat build
.\gradlew.bat bootRun
```

The expert expects PostgreSQL on `localhost:5432` with database `eia_retrocession` (user `postgres`, password `postgres`). `schema.sql` creates the table on startup.

## Responsibilities

- Keep this repository focused on one deployable component or one platform concern.
- Follow the repository standard in `../CONVENTIONS.md` and the specification in `../../SPEC.md`.
- Publish and consume contracts through `eia-contracts`.
- Maintain README, CODEOWNERS, SECURITY, CHANGELOG, tests, and local verification.
- Contain no business logic in shared libraries or front-end remotes.

## Verification

- Lint and format
- Unit tests with 100% business-logic coverage where applicable
- Contract tests
- Integration or Playwright tests where applicable
- Security scan and SBOM
- Build and publish

## Notes

Writes data; does not decide business outcomes.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
