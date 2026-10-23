# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

Mini Shop is a learning/portfolio e-commerce site (Java / Spring Boot, server-rendered). **No application code exists yet**: the repo currently holds only planning docs. The source of truth for scope, tech choices, and the phase plan is [docs/project-brief.md](docs/project-brief.md). The reasons behind key decisions are in [docs/adr/](docs/adr/README.md). Read it before starting any phase, and update this file once real commands and packages exist.

## How to work with the user

- The user is learning. Act as a senior Java developer pairing with them: when making a non-obvious design choice, briefly explain *why*. Converse in Vietnamese.
- Work strictly **phase by phase** (GĐ1–GĐ8 in the brief). Each phase must end runnable and testable. At the end of a phase, stop and report: what was done, how to run and verify it, and any decisions needed. Wait for approval before starting the next phase.
- Do not add features outside the brief's MVP list without asking first. The brief's "Loại ra" list names features that are explicitly excluded.
- Code identifiers, packages, and commit messages are in English. All user-facing UI text is in Vietnamese; prices are VND, formatted like `1.250.000 ₫`.

## Subagents and phase workflow

Project subagents live in `.claude/agents/`. Subagents cannot launch other subagents, so the main session orchestrates:

1. **`tech-lead`** (opus) plans the phase into `docs/plans/phase-<N>.md`: tasks, owner agent, files, dependencies, "done" condition, and questions for the user. Show the user the plan and its questions, and get approval before implementing.
2. Dispatch the implementation tasks to the owners. They split by file ownership, so writers never edit the same files in parallel:
   - **`backend-developer`**: `src/main/java`, migrations, `application*.yml` feature keys.
   - **`frontend-developer`**: `templates/`, `static/`.
   - **`devops-engineer`**: `pom.xml`, wrapper, `compose.yaml`, `Dockerfile`, `.github/`, infra config keys.
   - **`test-writer`**: `src/test`.
3. Run the checks, all read-only:
   - **`schema-guardian`** after any entity or migration change;
   - **`java-code-reviewer`** on the phase's diff;
   - **`phase-verifier`** last (build, run, browser checklist).
4. Report to the user in Vietnamese: what was done, how to run and verify it, and what needs a decision. Agents never commit or push; only do that when the user asks.

## UI design skill

All UI work uses the **`design-taste-frontend`** skill. The user-level copy in `~/.claude/skills/` is the one Claude Code loads. A project copy also sits in `.agents/skills/`, tracked by `skills-lock.json`. Its run depends on the area:
- **Storefront pages** follow the skill's page run.
- **`/admin/**`** follows its app-screen run (`references/app-screens.md`, `draw.mjs --app`, `inspect.mjs --app`).

The project's rules override the skill:
- Keep Thymeleaf + Bootstrap 5.3; do not migrate the stack.
- Build no extra features (the skill's "one live thing", scroll scenes, extra sections) without the user's approval.
- Use no invented content.
- Draw the direction once per area into `.taste-skill/`, then reuse it for every page.

The skill's scripts need Node 22+ and Chrome or Edge; both are installed.

## Planned stack (decided in the brief)

Java 25, Spring Boot 4.1.x (Web MVC, Data JPA/Hibernate 7, Security 7, Validation, Actuator health only), Thymeleaf + Bootstrap 5.3, PostgreSQL + Flyway, Maven Wrapper. Tests use JUnit 5, Mockito, AssertJ, MockMvc + spring-security-test, and Testcontainers.

Deliberately **not** used: Lombok (use records and explicit code), MapStruct, Thymeleaf Layout Dialect (use plain fragments), JS frameworks/SPA, JWT, microservices.

## Commands (available once GĐ1 creates the Maven project)

On Windows use `mvnw.cmd`; in Git Bash use `./mvnw`.

- Run: `./mvnw spring-boot:run`. `spring-boot-docker-compose` starts PostgreSQL automatically, so Docker must be running. App URL: http://localhost:8080
- Run without Docker: `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2` (H2 fallback; H2 console is only enabled in this profile)
- Full build + all tests: `./mvnw verify` (Testcontainers tests need Docker)
- Single test class / method: `./mvnw test -Dtest=OrderServiceTest` or `./mvnw test -Dtest=OrderServiceTest#placeOrder_outOfStock_rollsBack`

## Architecture (planned, see brief §6)

Modular monolith, **package-by-feature** under `com.minishop`: `catalog`, `cart`, `order`, `user`, `admin`, `storage`, `common`, `config`. Each feature contains its own controller → service → repository layers.

- **Dependency direction:** `order → cart → catalog`, never the reverse. `admin` holds only the web layer (controllers and forms) and calls other features' services; it never reimplements business logic.
- **All business logic lives in `@Transactional` services.** Controllers only bind and validate forms and pick views. `spring.jpa.open-in-view=false`, so services must fully load what views need (use fetch joins or DTOs).
- **Never bind entities from requests.** Forms are Java `record`s with Jakarta Validation; views receive DTOs.
- **Flyway owns the schema.** Schema changes go in new migration files, never via `ddl-auto=update`. Migrations must also run on H2 (PostgreSQL mode) for the `h2` profile.
- **Seed data** comes from a Java `DataInitializer` (passwords must be BCrypt-hashed at runtime). It only seeds an empty DB. Demo accounts: `admin@minishop.local`, `user@minishop.local`.

### Domain invariants to preserve

- **Stock:** decrement only via an atomic conditional update (`UPDATE ... SET stock = stock - :q WHERE id = :id AND stock >= :q`). If 0 rows are affected, the item is out of stock and the whole order rolls back. Never read stock, check it, and then write it.
- **Order placement** (`OrderService.placeOrder`) is one transaction: create Order and OrderItems, decrement stock, clear the cart.
- **OrderItem snapshots** `productName` and `unitPrice` at purchase time. It still keeps the `product` FK so stock can be restored on cancel.
- **Order status transitions** are encoded in `OrderStatus.canTransitionTo()`: `PENDING → CONFIRMED → SHIPPING → DELIVERED`. Cancel is allowed from `PENDING`/`CONFIRMED` (admin) or `PENDING` only (customer). Cancelling restores stock in the same transaction.
- **Products are soft-deleted** (`active=false`) because old OrderItems reference them. A category cannot be deleted while it still has products.
- **Money** uses `BigDecimal` / `numeric`.
- **Roles** are an enum (`USER`/`ADMIN`) on `User`; there is no Role table. Registration always assigns `USER`.
- **Cart:** guests keep their cart in the HTTP session; logged-in users have a DB `Cart`. On login, the session cart is merged into the DB cart.
- **Order access:** order ownership is checked in the service layer (IDOR protection). A user can only see their own orders.
- **Images:** go through the `StorageService` interface. The local implementation writes UUID-named files to `uploads/` (gitignored) after validating extension, content type, and size (≤ 2 MB). Keeping it an interface lets a cloud implementation replace it for deployment.

## Repository conventions

GitHub: `Luffyreals/mini-shop` (private), default branch `main`. The full rules are in [CONTRIBUTING.md](CONTRIBUTING.md). The essentials:

- Never commit directly to `main`. Each phase works on `phase-<N>/<slug>`; other work uses `fix/…`, `docs/…`, `chore/…`. Open a PR using `.github/pull_request_template.md`, then squash-merge.
- Commit messages follow Conventional Commits in English, with the feature name as scope: `feat(order): …`, `test(cart): …`.
- When a phase is done, move `[Unreleased]` in `CHANGELOG.md` to a release and tag `v0.<N>.0`.
- Any change to architecture, technology, or a domain invariant gets a new ADR in `docs/adr/`. Accepted ADRs are never edited; a new ADR supersedes the old one.
- Line endings are normalized to LF by `.gitattributes` (CRLF only for `*.cmd`). Formatting rules are in `.editorconfig`.
