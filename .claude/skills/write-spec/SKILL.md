---
name: write-spec
description: Draft or update a Mini Shop feature spec in docs/specs/ (user stories, Given/When/Then acceptance criteria, business rules, URLs, data, open questions) from the project brief and existing ADRs. Use at the start of a phase (GĐ1–GĐ8), when the user asks to "viết spec", "tạo spec", "đặc tả" a feature, or when a requirement changes mid-phase.
argument-hint: "<phase number or feature name>"
---

# write-spec

Produce one spec file that states **what** a feature must do and **how we will know it is done**. Leave the **how** to `tech-lead`'s plan.

## Inputs to read first
1. `docs/specs/README.md` (process, conventions) and `docs/specs/_template.md` (structure).
2. `docs/project-brief.md`: the MVP list, the "Loại ra" list, and the phase table in §11. The spec must stay inside this scope.
3. `CLAUDE.md` (domain invariants) and every ADR in `docs/adr/` related to the feature.
4. Existing specs, so you do not contradict or duplicate them, and the current code if the feature touches it.

## Steps
1. Work out which phase or feature the argument means. If a spec for it already exists, update that file instead of creating a new one, and add a row to its *Lịch sử thay đổi*.
2. Number a new spec as the next free `NNN`, and name the file `docs/specs/NNN-<kebab-name>.md`.
3. Fill in every template section, in Vietnamese:
   - **Scope:** state explicitly what is *out* of scope; copy relevant items from the brief's "Loại ra" list.
   - **Acceptance criteria:**
     - Each AC is testable, has an id `AC-NNN-n`, and names how it will be checked: a test class/method to be written, or "checklist".
     - Cover the happy path, validation errors, permissions (anonymous / USER / ADMIN, plus other users' data), empty states, and the domain invariants the feature touches (stock, snapshots, status transitions).
   - **Exact Vietnamese UI text** for messages that tests or the verifier will look for.
   - **Data:** only the tables and columns this feature adds or changes, with their constraints.
4. Anything you would otherwise have to guess goes in **Câu hỏi mở**, with your recommended answer. Never invent requirements, and never add features beyond the brief.
5. Set the status to `Nháp` and add or update the spec's row in `docs/specs/README.md`.

## Output to the user
Summarize in Vietnamese:
- the spec file path;
- the number of user stories and ACs;
- the open questions, each with your recommendation.

Then ask the user to approve the spec. When they approve, set the status to `Đã duyệt`. Only an approved spec goes to `tech-lead` for planning.
