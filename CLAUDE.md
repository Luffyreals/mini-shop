# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

Mini Shop is a learning/portfolio e-commerce site (Java / Spring Boot, server-rendered). GĐ1 ([spec 001](docs/specs/001-project-foundation.md), [plan](docs/plans/phase-1.md)) is **done and released as `v0.1.0`**. It delivered:
- the skeleton: Maven, profiles, security baseline with CSP, a placeholder home page, and CI;
- the Flyway `V1` schema for 7 tables;
- entities and aggregate-root repositories, including `ProductRepository.decrementStock`;
- the `DataInitializer` seed;
- architecture, data, and security tests.

GĐ2 ([spec 002](docs/specs/002-product-catalog.md), [plan](docs/plans/phase-2.md)) is **done and released as `v0.2.0`**: the storefront product listing and detail pages. GĐ3 ([spec 003](docs/specs/003-authentication.md), [plan](docs/plans/phase-3.md)) is **done and released as `v0.3.0`**: registration, login/logout, `USER`/`ADMIN` access rules, the `/admin` placeholder and the 403 page. Next is GĐ4 (cart), which starts with `/write-spec`. The source of truth for scope, tech choices, and the phase plan is [docs/project-brief.md](docs/project-brief.md). The reasons behind key decisions are in [docs/adr/](docs/adr/README.md). Read it before starting any phase, and update this file once real commands and packages exist.

## How to work with the user

- The user is learning. Act as a senior Java developer pairing with them: when making a non-obvious design choice, briefly explain *why*. Converse in Vietnamese.
- Work strictly **phase by phase** (GĐ1–GĐ8 in the brief). Each phase must end runnable and testable. At the end of a phase, stop and report: what was done, how to run and verify it, and any decisions needed. Wait for approval before starting the next phase.
- Do not add features outside the brief's MVP list without asking first. The brief's "Loại ra" list names features that are explicitly excluded.
- Code identifiers, packages, and commit messages are in English. All user-facing UI text is in Vietnamese; prices are VND, formatted like `1.250.000 ₫`.

## Subagents and phase workflow

Project subagents live in `.claude/agents/`. Subagents cannot launch other subagents, so the main session orchestrates:

0. **Spec first.** Use the `/write-spec` skill (`.claude/skills/write-spec/`) to write or update `docs/specs/NNN-*.md`: user stories, `AC-NNN-n` acceptance criteria (Given/When/Then), business rules, URLs, data, and open questions. The user approves the spec (status `Đã duyệt`) before planning. The process is described in `docs/specs/README.md`.
1. **`tech-lead`** (opus) plans the phase from the approved spec into `docs/plans/phase-<N>.md`: tasks, owner agent, files, dependencies, "done" condition, and questions for the user. Show the user the plan and its questions, and get approval before implementing.
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

All UI work uses the **`design-taste-frontend`** skill. It is installed only at user level, in `~/.claude/skills/design-taste-frontend`, **on this machine**. It is not part of the repo, so another machine has to install it separately. Its run depends on the area:
- **Storefront pages** follow the skill's page run.
- **`/admin/**`** follows its app-screen run (`references/app-screens.md`, `draw.mjs --app`, `inspect.mjs --app`).

The project's rules override the skill:
- Keep Thymeleaf + Bootstrap 5.3; do not migrate the stack.
- Build no extra features (the skill's "one live thing", scroll scenes, extra sections) without the user's approval.
- Use no invented content.
- Draw the direction once per area into `.taste-skill/`, then reuse it for every page.
- **The CSP is enforced** (`default-src 'self'`, see `SecurityConfig`). Templates must have no inline `<script>`, no `style="…"` / `th:style`, no `onclick` or other inline handlers, and no external fonts or CDNs. All CSS and JS live in `static/`, and images are self-hosted or `data:`.

The skill's scripts need Node 22+ and Chrome or Edge; both are installed.

## Planned stack (decided in the brief)

Java 25, Spring Boot 4.1.x (Web MVC, Data JPA/Hibernate 7, Security 7, Validation, Actuator health only), Thymeleaf + Bootstrap 5.3, PostgreSQL + Flyway, Maven Wrapper. Tests use JUnit 5, Mockito, AssertJ, MockMvc + spring-security-test, and Testcontainers.

Deliberately **not** used: Lombok (use records and explicit code), MapStruct, Thymeleaf Layout Dialect (use plain fragments), JS frameworks/SPA, JWT, microservices.

## Commands

On Windows use `mvnw.cmd`; in Git Bash use `./mvnw`. The first run downloads Maven itself.

- **Run:** `./mvnw spring-boot:run`. `spring-boot-docker-compose` starts PostgreSQL from `compose.yaml` automatically, so Docker Desktop must be running. App URL: http://localhost:8080
- **Run without Docker:** `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`. This uses in-memory H2 in PostgreSQL mode. The H2 console at `/h2-console` exists only in this profile.
- **Full build and all tests:** `./mvnw verify`. Tests annotated `@Testcontainers(disabledWithoutDocker = true)` are *skipped* when Docker is not running. CI (`.github/workflows/ci.yml`) always runs them.
- **Single test class / method:** `./mvnw test -Dtest=HomeControllerTest` or `./mvnw test -Dtest=HomeControllerTest#home_isPublicAndRendersShopName`
- **Skipped-test guard:** after `verify`, run `bash .github/scripts/check-no-skipped-tests.sh`. It exits 1 if any test was skipped, and CI runs it. Never leave `@Disabled` tests on `main`.
- **Coverage:** `./mvnw verify` writes `target/site/jacoco/index.html` (JaCoCo, no threshold yet), and CI uploads it as the `jacoco-report` artifact. If surefire `argLine` is ever set, use `@{argLine}` so the JaCoCo agent is kept.
- **Architecture rules are tests:** `ArchitectureTest` (ArchUnit) fails the build on a forbidden feature dependency, a dependency cycle, a controller touching a repository, or field injection. Update it together with `package-info.java` when a dependency rule changes.
- **Without Docker:** `H2ProfileSmokeTest` still proves that V1, Hibernate `validate`, and the seed work on H2.
- **Demo accounts:** seeded from `app.seed.*` (`SEED_ADMIN_PASSWORD` / `SEED_USER_PASSWORD`, dev defaults in `application.yml`).

Spring Boot 4 notes (APIs moved from 3.x):
- Starters are modular: `spring-boot-starter-webmvc`, `spring-boot-starter-flyway`, and per-module `*-test` starters.
- Test slices live in new packages, e.g. `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` / `AutoConfigureMockMvc`.
- Testcontainers 2.x uses `org.testcontainers.postgresql.PostgreSQLContainer`.
- Integration tests import `TestcontainersConfiguration` (pinned `postgres:17-alpine`, the same image as `compose.yaml`). `TestMiniShopApplication` runs the app against a throwaway container.

## Architecture (planned, see brief §6)

Modular monolith, **package-by-feature** under `com.minishop`: `catalog`, `cart`, `order`, `user`, `admin`, `storage`, `common`, `config`. Each package's `package-info.java` states its responsibility and which features it may depend on.

- **Feature packages are flat.** Use `Product`, `ProductRepository`, `ProductService`, `ProductController` side by side, with no `web/` or `domain/` sub-packages.
- **Default to package-private.** Make classes and methods public only when another feature must call them; that is how module boundaries are enforced.
- **Bootstrap 5.3.8 is loaded via WebJars**, with version-less URLs thanks to `webjars-locator-lite`, e.g. `@{/webjars/bootstrap/dist/css/bootstrap.min.css}`. Project CSS lives in `static/css/app.css`.

- **Dependency direction:** `order → cart → catalog`, never the reverse. `admin` holds only the web layer (controllers and forms) and calls other features' services; it never reimplements business logic.
- **All business logic lives in `@Transactional` services.** Controllers only bind and validate forms and pick views. `spring.jpa.open-in-view=false`, so services must fully load what views need (use fetch joins or DTOs).
- **Never bind entities from requests.** Forms are Java `record`s with Jakarta Validation; views receive DTOs.
- **Flyway owns the schema.** Schema changes go in new migration files, never via `ddl-auto=update`. Migrations must also run on H2 (PostgreSQL mode) for the `h2` profile.
- **Seed data** comes from `config.DataInitializer`, an `ApplicationRunner`. Passwords are hashed at runtime by the `DelegatingPasswordEncoder` from `config.PasswordEncoderConfig` (`{bcrypt}…`). Users and catalog have independent "table is empty" guards. Demo accounts: `admin@minishop.local`, `user@minishop.local`.
- **Repositories exist only for aggregate roots** (`User`, `Category`, `Product`, `Cart`, `Order`). `CartItem` and `OrderItem` are saved only through their parent (`cascade = ALL`, `orphanRemoval`). Use `Cart.addItem`/`removeItem` and `Order.addItem`; `getItems()` is read-only.
- **The seed must be gated before any deployment (GĐ8):** with the dev defaults, an empty production DB would get `admin@minishop.local` with a known password. Add `app.seed.enabled` or remove the defaults before deploying. Also before deploying: there is no login rate limiting or account lockout yet (spec 003, open question 7), and the session cookie should get `same-site=lax` and `secure=true` behind HTTPS.
- **Visibility today:** `user.User`, `user.Role`, `user.UserRepository`, `user.CurrentUser`, `catalog.Category`, `catalog.Product` and the catalog repositories are public; everything in `cart` and `order` is package-private.
- **Entity conventions:** a protected no-arg constructor plus a constructor taking the required fields; setters only where used. `created_at` and `updated_at` are set by `@PrePersist`/`@PreUpdate`. Money is `numeric(12,0)` with whole VND, so keep amounts integral in services.

### Storefront patterns (GĐ2), reuse these in later phases

- **Query parameters arrive as strings.** `ProductController` takes `q`, `category`, `sort` and `page` as raw `String`s in a `ProductSearchParams` record, and `ProductFilter.from()` normalises them: trim, cap `q` at 100 chars, 1-based page, unknown values fall back to the defaults. Never bind `sort`/`page` straight into `Pageable`; a bad value would become a 500.
- **Product queries** use Spring Data `Specification` (`ProductSpecifications`) plus `@EntityGraph("category")` on the repository's `findAll(spec, pageable)`. Never call `root.fetch()` inside a Specification, because it breaks the count query. Sorts always add `id` as a tie-breaker so pages are stable. LIKE input is escaped (`%`, `_`, `\`).
- **Views receive records**, never entities: `ProductCardView`, `ProductDetailView`, `ProductListView`, `CategoryOption`. Categories are sorted in Java with a Vietnamese `Collator`, not by the DB.
- **Prices:** always `${#vnd.format(price)}`, the `ViewHelpersDialect` expression object (`common.VndFormatter`). Never format money inline in a template.
- **Pagination:** `common.PageNav.of(current, totalPages, urlForPage)` builds the links; templates render `fragments/pagination :: pagination(nav)` and never build URLs themselves.
- **404:** throw `common.NotFoundException`. `catalog.ProductNotFoundAdvice` maps it, plus `MethodArgumentTypeMismatchException` (e.g. `/products/abc`), to status 404 and view `product/not-found`. The general error pages come in GĐ7.
- **Templates:**
  - Shared pieces live in `templates/fragments/`: `layout` (head/header/footer), `product` (tile/stock/card) and `pagination`. Page templates live in `templates/<feature>/`.
  - `TemplateCspComplianceTest` scans every template and static HTML file for CSP violations.
- **Design direction:** "The specimen catalogue" (tokens in the opening comment of `static/css/app.css`; working files in `.taste-skill/`, gitignored). Reuse it; never redraw for the storefront.
- **Assets:** fonts are served from `/fonts/**` (public path). Every new image needs a licence entry in `static/credits.html` (CC BY/BY-SA require attribution in the app), which the footer links as "Nguồn ảnh". Product image paths are `/images/products/<slug>.jpg`.

### Auth patterns (GĐ3), reuse these in later phases

See [ADR-0007](docs/adr/0007-session-form-login-current-user.md) and [spec 003](docs/specs/003-authentication.md).

- **The principal is `user.CurrentUser`** (id, email, fullName, role), never the `User` entity. Its password hash is erased after login. Later features get the current user with `@AuthenticationPrincipal CurrentUser` and use `getId()`; templates read `${#authentication.principal.fullName}`.
- **Emails always go through `user.EmailNormalizer`** (strip + lower-case `Locale.ROOT`), for both registration and login. The DB enforces it with `ck_users_email_lowercase` (V2). Registration accepts ASCII emails only.
- **Duplicate registration** is handled in `UserService.register`: pre-check plus `saveAndFlush` and a catch that maps only the `uq_users_email` violation (matched with `contains`, because H2 prefixes the schema) to `EmailAlreadyRegisteredException`.
- **Security rules** live in `config.SecurityConfig`, most specific first: `/admin/**` → `ADMIN`; `/orders/**`, `/checkout/**` → authenticated; public paths are GET-only; `GET /login` is opened by path; `/register`, `/access-denied` and `/error` are open for all methods, because forwards and error dispatches keep the original method (a failed POST becomes `POST /error`). `formLogin().permitAll()` matches the URL *including the query string*, so with a custom failure handler it would not open `/login?error`. In MockMvc, test such URLs as `get("/login?error")`: `.param("error", "")` creates no query string and hides this kind of bug. Any new page that needs login is covered by `anyRequest().authenticated()`; any new public page must be added to `PUBLIC_PATHS` deliberately.
- **One-time messages:** a controller redirect uses `RedirectAttributes.addFlashAttribute`. Redirects issued by Spring Security use a query parameter (`/login?error`, `/?logout`), because logout destroys the session before redirecting. `LoginFailureHandler` keeps the typed email through `FlashMapManager`.
- **Forms:** every POST form uses `th:action` (that is what adds the `_csrf` field). Password inputs never use `th:field` or `th:value`, because `th:field` re-renders the rejected value. Form records mask passwords in `toString()`.
- **403:** `accessDeniedPage("/access-denied")` forwards to `common.AccessDeniedController`, which renders `error/403.html` with status 403. GĐ7 adds the other error pages next to it.
- **Logged-in users** opening `/login` or `/register` are redirected to `/` by `AuthController` (checks the `Principal` parameter).
- **Tests:** web tests that render a page for a logged-in user need a real `CurrentUser` principal (the `TestUsers` fixture), because `@WithMockUser` has no `fullName`. Filter-only tests (`SecurityConfigTest`) can keep `@WithMockUser`.
- **GĐ4 cart merge:** hook it on successful login (an `AuthenticationSuccessEvent` listener or a wrapping success handler) and keep the saved-request redirect behaviour.

### Domain invariants to preserve

- **Stock:** decrement only via an atomic conditional update (`ProductRepository.decrementStock`: `... WHERE id = :id AND :q > 0 AND stock >= :q`). If 0 rows are affected, the item is out of stock and the whole order rolls back. Never read stock, check it, and then write it.
- `Product.stock` is mapped `updatable = false`, so saving a loaded `Product` (admin edit) can never overwrite stock that was decremented in the meantime. Any later stock change (admin restock, restoring stock on cancel) must use its own `@Modifying` query.
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
