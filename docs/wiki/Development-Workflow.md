# Development Workflow

OneClaw follows a documentation-driven development approach. Documentation is the single source of truth -- code is generated from RFCs and can be fully regenerated at any time.

## Core Philosophy

1. **Documentation as Source of Truth** -- Code can be rewritten; requirements and design documents persist
2. **Pure Documentation-Driven AI Development** -- AI generates code based on PRD and RFC, without referencing existing implementations
3. **Automated Verification** -- Comprehensive testing ensures quality
4. **Reproducibility** -- The application can be regenerated from documentation

## Workflow: PRD -> RFC -> Code -> Test

### 1. Requirements Phase (PRD)

Create a Product Requirements Document at `docs/prd/features/FEAT-XXX-feature-name.md`.

A PRD defines:
- Feature ID and metadata
- User story (As a..., I want to..., So that...)
- Typical usage scenarios
- Feature description (overview + detailed specification)
- Acceptance criteria

### 2. Design Phase (RFC)

Create a Technical Design Document at `docs/rfc/features/RFC-XXX-feature-name.md`.

An RFC defines:
- Technical approach and architecture
- API interfaces and data structures
- Database schema changes
- UI component specifications
- Implementation details sufficient for code generation

### 3. Development Phase (Code)

Generate code based on the RFC. The RFC should contain enough detail that an AI can produce the implementation without looking at existing code.

Key conventions:
- Follow Clean Architecture layers (core -> data -> feature)
- Use `AppResult<T>` for fallible operations
- Register new components in the appropriate Koin module
- Add navigation routes for new screens

### 4. Testing Phase

After implementing an RFC, run the testing protocol:

1. **Layer 1A** -- `./gradlew test` (all JVM tests must pass)
2. **Layer 1B** -- `ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest` (skip if no emulator)
3. **Layer 1C** -- `./gradlew verifyRoborazziDebug` for UI changes
4. **Layer 2** -- Manual adb verification flows
5. **Write test report** -- `docs/testing/reports/RFC-XXX-<name>-report.md`
6. **Update manual test guide** -- `docs/testing/manual-test-guide.md`

## ID System

| Prefix | Purpose | Example |
|--------|---------|---------|
| `FEAT-XXX` | Product requirement | `FEAT-001` (Chat Interaction) |
| `RFC-XXX` | Technical design | `RFC-001` (Chat Interaction) |
| `ADR-XXX` | Architecture decision | `ADR-001` |
| `TEST-XXX` | Test scenario | `TEST-001` |

Feature IDs and RFC IDs typically share the same number (FEAT-001 maps to RFC-001).

## Bilingual Documentation

All documents must exist in two languages:
- **English:** `filename.md`
- **Chinese:** `filename-zh.md`

Workflow: Write the English version first, then translate to Chinese. The two versions must be kept in sync.

## Adding a New Feature

1. Assign the next available `FEAT-XXX` ID
2. Write `docs/prd/features/FEAT-XXX-feature-name.md` (English)
3. Translate to `docs/prd/features/FEAT-XXX-feature-name-zh.md` (Chinese)
4. Write `docs/rfc/features/RFC-XXX-feature-name.md` (English)
5. Translate to `docs/rfc/features/RFC-XXX-feature-name-zh.md` (Chinese)
6. Implement the code based on the RFC
7. Run the testing protocol
8. Write the test report (English + Chinese)

## Modifying an Existing Feature

1. Update the PRD with new requirements
2. Update the RFC with revised technical design
3. Regenerate or modify the code
4. Run the testing protocol
5. Update the test report

## Document Templates

Templates are available at:
- PRD: `docs/prd/_template.md`
- RFC: `docs/rfc/_template.md`
- ADR: `docs/adr/_template.md`

## Directory Structure

```
docs/
├── prd/
│   ├── _template.md
│   ├── 00-overview.md
│   └── features/
│       ├── FEAT-001-chat.md
│       ├── FEAT-001-chat-zh.md
│       └── ...
├── rfc/
│   ├── _template.md
│   ├── architecture/
│   │   └── RFC-000-*.md
│   └── features/
│       ├── RFC-001-chat-interaction.md
│       ├── RFC-001-chat-interaction-zh.md
│       └── ...
├── adr/
│   └── ADR-*.md
├── testing/
│   ├── strategy.md
│   ├── manual-test-guide.md
│   └── reports/
│       └── RFC-XXX-*-report.md
└── wiki/
    └── (this documentation)
```
