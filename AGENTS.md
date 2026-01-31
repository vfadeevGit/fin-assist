# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/ru/stnovator/finassist` holds the Jmix/Spring Boot application. Key areas: `entity/` (domain), `security/`, `view/` (FlowUI views), and `FinAssistApplication.java` (entry point).
- `src/main/resources` contains configuration (`application.properties`, `application-dev.properties`, `application-prod.properties`) and UI assets under `META-INF/resources`.
- `frontend/` stores Vaadin/Jmix FlowUI client assets and themes (`frontend/themes`).
- `src/test/java/ru/stnovator/finassist` contains JUnit tests and shared test helpers.
- `docker/docker-compose.yml` defines local Postgres, pgAdmin, and an app container.

## Build, Test, and Development Commands
- `./gradlew bootRun` runs the app with the `dev` profile (see args in `build.gradle`).
- `./gradlew test` runs the JUnit 5 test suite.
- `./gradlew build` compiles and packages the application.
- `./gradlew bootBuildImage` builds a container image (uses the `prod` profile).
- `docker compose -f docker/docker-compose.yml up -d` starts Postgres, pgAdmin, and the app container.

## Coding Style & Naming Conventions
- Java: 4-space indentation, standard Jmix/Spring Boot conventions.
- Keep code under `ru.stnovator.finassist`.
- Class names use PascalCase; methods and fields use camelCase.
- UI view classes live in `view/<feature>/...` (for example, `view/customer/CustomerDetailView.java`).

## Testing Guidelines
- Framework: JUnit 5 via `spring-boot-starter-test`.
- UI tests use `jmix-flowui-test-assist`.
- Naming: `*Test` and `*UiTest` (for example, `UserTest.java`, `UserUiTest.java`).
- Put shared helpers in `src/test/java/.../test_support`.

## Commit & Pull Request Guidelines
- Commits: short, sentence case, past tense (for example, "Added ...", "Fixed ...").
- PRs should include: a concise summary, tests run (or "Not run"), screenshots for UI changes, and related issue links when available.

## Configuration & Data
- Local DB defaults to Postgres; see credentials and ports in `docker/docker-compose.yml`.
- Environment-specific settings live in `application-*.properties`. Keep secrets out of VCS.
