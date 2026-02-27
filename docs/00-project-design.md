# OneClaw Shadow Project Design Document

## Document Purpose

This document details the design philosophy, workflow, and best practices for the OneClaw Shadow project. This is an experimental project aimed at exploring new software development patterns in the AI era.

## Core Philosophy

### 1. Documentation is the Single Source of Truth

In traditional development, code is the source of truth. But in the age of AI-assisted development, we propose:

**Documentation (PRD + RFC) is the single source of truth; code is merely an implementation of the documentation.**

This means:
- Any feature must have documentation before code
- Code can be regenerated from documentation at any time
- Changes to documentation drive changes to code
- Understanding the system means reading the documentation, not the code

### 2. Pure Documentation-Driven AI Development

Traditional AI-assisted development: humans write code -> AI completes/optimizes code
New paradigm: humans write documentation -> AI generates code from documentation

Key constraints:
- **AI does not look at existing code**: Avoids inheriting technical debt
- **AI only reads PRDs and RFCs**: Ensures implementation fully aligns with design
- **Reproducibility**: Can regenerate from scratch at any time

Advantages:
- Forces documentation and code to stay in sync
- Facilitates refactoring and tech stack migration
- Reduces technical debt accumulation
- New team members can understand the system through documentation alone

### 3. Automated Verification Eliminates Manual Bottlenecks

A problem discovered in previous development: **Manual testing becomes a bottleneck**

Solution:
- Use structured formats (YAML) to describe test scenarios
- AI generates automated test code from test scenarios
- CI/CD automatically runs all tests
- Humans only need to write test scenarios, not manually execute them

### 4. Traceable Requirements-Design-Implementation-Testing Chain

Establish complete traceability:
```
FEAT-001 (PRD)
  |
RFC-001 (Technical Design)
  |
Code Implementation
  |
TEST-001 (Test Scenario)
  |
Automated Test Code
```

Each link has a clear correspondence, enabling:
- Tracing from feature back to design documentation
- Tracing from code back to requirements documentation
- Tracing from tests back to feature requirements

## Project Structure in Detail

### Documentation Hub (docs/)

This is the core of the project. All requirements and designs live here.

#### PRD Directory Structure
```
docs/prd/
├── 00-overview.md              # Product overview
├── _template.md                # PRD template
├── features/                   # Feature module PRDs
│   ├── FEAT-001-auth.md
│   ├── FEAT-002-cloud-storage.md
│   └── FEAT-003-file-management.md
└── versions/                   # Version planning
    ├── v1.0-mvp.md
    └── v1.1-iteration.md
```

**PRD Writing Principles**:
- Describe "what" and "why", not "how"
- User perspective, focused on user value
- Clear scope: what is included, what is not
- Verifiable: has clear acceptance criteria

#### RFC Directory Structure
```
docs/rfc/
├── _template.md                # RFC template
├── architecture/               # Architecture design
│   ├── 001-overall-architecture.md
│   ├── 002-data-layer.md
│   └── 003-ui-layer.md
└── features/                   # Feature implementation proposals
    ├── RFC-001-auth-implementation.md
    ├── RFC-002-cloud-storage-implementation.md
    └── RFC-003-file-management-implementation.md
```

**RFC Writing Principles**:
- Describes "how" -- technical implementation details
- Includes data models, API design, code examples
- Detailed enough for AI to implement directly
- Considers performance, security, and scalability

#### ADR (Architecture Decision Records)
```
docs/adr/
├── _template.md
├── 001-use-jetpack-compose.md
├── 002-use-clean-architecture.md
└── 003-use-room-database.md
```

Records important technical decisions:
- Why this decision was made
- What alternatives were considered
- Pros and cons of each option
- Impact and risks of the decision

### Testing Hub (tests/)

A comprehensive testing system that eliminates the manual testing bottleneck.

#### Test Directory Structure
```
tests/
├── plans/                      # Test plans
│   ├── integration-test-plan.md
│   └── e2e-test-plan.md
├── scenarios/                  # Test scenarios (YAML)
│   ├── _template.yaml
│   ├── TEST-001-login-flow.yaml
│   ├── TEST-002-file-upload.yaml
│   └── TEST-003-error-handling.yaml
├── unit/                       # Unit tests (generated code)
│   └── [AI-generated unit tests]
├── integration/                # Integration tests (generated code)
│   └── [AI-generated integration tests]
└── e2e/                       # End-to-end tests (generated code)
    └── [AI-generated E2E tests]
```

**Role of Test Scenarios**:
- Describe test steps and expected results in YAML
- Humans write scenarios, AI generates test code
- Scenario files serve as both test documentation and test specification
- Tests can be designed before any code exists

#### Three-Layer Testing Strategy

1. **Unit Tests**
   - Target: Independent classes and functions
   - Coverage: ViewModel, UseCase, Repository
   - Coverage goal: > 80%
   - Execution speed: Fast (milliseconds)

2. **Integration Tests**
   - Target: Interaction between multiple components
   - Coverage: Database operations, API calls, data flows
   - Execution speed: Medium (seconds)

3. **End-to-End Tests (E2E)**
   - Target: Complete user workflows
   - Coverage: Critical business processes
   - Execution speed: Slow (minutes)
   - Tools: Jetpack Compose UI Test / Espresso

### Code Directory (app/)

Android application code, generated by AI based on RFCs.

Uses Clean Architecture + MVVM:
```
app/src/main/kotlin/com/example/oneclaw/
├── data/           # Data layer
│   ├── local/      # Local data source (Room)
│   ├── remote/     # Remote data source (Retrofit)
│   └── repository/ # Repository implementations
├── domain/         # Domain layer
│   ├── model/      # Domain models
│   ├── repository/ # Repository interfaces
│   └── usecase/    # Use cases
└── ui/             # UI layer
    ├── theme/      # Theme
    ├── components/ # Common components
    └── features/   # Feature modules (Screen + ViewModel)
```

**Code Organization Principles**:
- Strict layered architecture
- Unidirectional dependency: UI -> Domain -> Data
- Modularized by feature
- High cohesion, low coupling

## Complete Workflow

### Full Process for Adding a New Feature

#### Step 1: Requirements Phase
```
1. Create PRD document: docs/prd/features/FEAT-XXX-feature-name.md
2. Fill in using the PRD template:
   - User stories
   - Feature description
   - Acceptance criteria
   - UI/UX requirements
3. (Optional) Have AI help refine the PRD
4. Review PRD, confirm requirements are clear
```

#### Step 2: Design Phase
```
1. Create RFC document: docs/rfc/features/RFC-XXX-feature-name.md
2. Fill in using the RFC template:
   - Architecture design
   - Data model
   - API design
   - Implementation steps
3. (Optional) Have AI provide technical solution suggestions
4. Review RFC, confirm design is feasible
5. (If major technical decisions) Create ADR document
```

#### Step 3: Test Design Phase
```
1. Create test scenarios: tests/scenarios/TEST-XXX-scenario-name.yaml
2. Describe test steps and expected results
3. Include both normal and exceptional flows
4. Review test scenarios, ensure complete coverage
```

#### Step 4: Development Phase
```
1. Provide AI with the RFC document
2. Explicitly tell AI: implement only based on the RFC, do not reference existing code
3. AI generates code
4. Review code architecture and key logic
5. Commit code
```

#### Step 5: Test Implementation Phase
```
1. Provide AI with the test scenario YAML files
2. AI generates test code from the scenarios:
   - Unit tests
   - Integration tests
   - UI tests
3. Review test code
4. Commit test code
```

#### Step 6: Automated Verification Phase
```
1. Run unit tests: ./gradlew test
2. Run integration tests: ./gradlew connectedAndroidTest
3. Run E2E tests: ./scripts/run-e2e-tests.sh
4. Check test coverage: ./gradlew jacocoTestReport
5. All tests pass -> Feature complete
```

#### Step 7: Iteration and Refactoring
```
If modifications are needed:
1. Modify the PRD or RFC documents
2. Provide AI with the new documents
3. AI regenerates the code
4. Run automated tests to verify
5. Compare the new and old implementations
```

### Full Process for Refactoring the Entire Application

This is the scenario of most interest: regenerating the entire application from documentation.

```
Scenario: Existing application has too much technical debt, needs refactoring

Steps:
1. Ensure PRD and RFC documents are complete and up to date
2. Create a new code branch or new directory
3. Provide AI with all RFC documents
4. Explicitly tell AI: implement from scratch, do not look at old code
5. AI generates entirely new code
6. Run all tests to verify feature completeness
7. Compare new and old implementations:
   - Code quality
   - Performance metrics
   - Test coverage
8. Decide whether to adopt the new implementation

Advantages:
- Completely eliminates technical debt
- Applies the latest tech stack
- Code quality is guaranteed (based on clear RFCs)
- Feature completeness is guaranteed (verified by automated tests)
```

## ID and Naming Conventions

### Feature ID Convention
- Format: `FEAT-XXX`
- Numbering: Starting from 001, incrementing sequentially
- Example: `FEAT-001`, `FEAT-002`

### RFC ID Convention
- Format: `RFC-XXX`
- Numbering: Starting from 001, incrementing sequentially
- Naming: `RFC-XXX-brief-description.md`
- Example: `RFC-001-auth-implementation.md`

### ADR ID Convention
- Format: `ADR-XXX`
- Numbering: Starting from 001, incrementing sequentially
- Naming: `XXX-decision-summary.md`
- Example: `001-use-jetpack-compose.md`

### Test ID Convention
- Format: `TEST-XXX`
- Numbering: Starting from 001, incrementing sequentially
- Naming: `TEST-XXX-scenario-description.yaml`
- Example: `TEST-001-login-flow.yaml`

## Documentation Writing Best Practices

### PRD Best Practices

Good PRD:
```markdown
## User Story
As a user, I want to be able to log into the app so that I can access my personal data.

## Acceptance Criteria
- [ ] User can log in with email and password
- [ ] Incorrect password displays an error message
- [ ] Successful login redirects to the home page
- [ ] Login state is persisted
```

Bad PRD:
```markdown
Implement a login feature using JWT tokens, stored in SharedPreferences.
```
(This describes technical implementation details, which belongs in the RFC)

### RFC Best Practices

Good RFC:
```kotlin
// Clear data model
data class User(
    val id: String,
    val email: String,
    val name: String
)

// Clear interface definition
interface AuthRepository {
    suspend fun login(email: String, password: String): Result<User>
}

// Clear implementation steps
1. Create LoginViewModel
2. Create LoginUseCase
3. Create AuthRepository implementation
4. Create LoginScreen UI
```

Bad RFC:
```markdown
Implement login feature, refer to existing code for implementation details.
```
(Not specific enough for AI to implement)

### Test Scenario Best Practices

Good test scenario:
```yaml
steps:
  - action: "Enter email"
    target: "email_field"
    data: "test@example.com"
    expected:
      - "Email input field displays test@example.com"
    verification:
      - type: "text_equals"
        target: "email_field"
        value: "test@example.com"
```

Bad test scenario:
```yaml
steps:
  - action: "Test login"
    expected: "Can log in"
```
(Not specific enough to automate)

## Tools and Scripts

### Development Scripts (scripts/)

```
scripts/
├── setup.sh              # Project initialization
├── build.sh              # Build application
├── run-tests.sh          # Run all tests
├── generate-docs.sh      # Generate documentation
└── check-coverage.sh     # Check test coverage
```

### Documentation Tools (tools/)

```
tools/
├── validate-docs.py      # Validate document format and completeness
├── generate-test.py      # Generate test code from YAML
└── doc-graph.py          # Generate document dependency graph
```

## CI/CD Integration

### GitHub Actions Workflow

```yaml
# .github/workflows/ci.yml
name: CI

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
      - name: Setup JDK
      - name: Run unit tests
      - name: Run integration tests
      - name: Generate coverage report
      - name: Upload coverage to Codecov
  
  validate-docs:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
      - name: Validate PRD/RFC format
      - name: Check traceability
```

## Metrics and Improvement

### Key Metrics

1. **Documentation Quality**
   - PRD completeness: Are all required fields filled in
   - RFC detail level: Is it detailed enough for AI to implement
   - Documentation coverage: How many features have PRDs and RFCs

2. **Test Quality**
   - Code coverage: > 80%
   - Test scenario coverage: Are all critical flows covered
   - Automation rate: How many tests are automated

3. **Development Efficiency**
   - Time from PRD to working feature
   - Refactoring time: Time to refactor based on documentation
   - Bug rate: Number of bugs in production

4. **Reproducibility**
   - Can code be regenerated from documentation
   - Time for new team members to understand the system

### Continuous Improvement

Regular reviews:
- Does the PRD template need adjustment
- Is the RFC template detailed enough
- Are test scenarios easy to write
- What is the quality of AI-generated code

## Experimental Goals

The experimental goals of this project:

1. **Validate the feasibility of pure documentation-driven development**
   - Can AI implement a complete application based solely on documentation
   - How detailed must documentation be for AI to understand

2. **Validate the ease of refactoring**
   - Can applications be quickly refactored based on documentation
   - Quality comparison between new and old implementations

3. **Validate the effectiveness of automated testing**
   - Can automated testing replace manual testing
   - Are test scenarios easy to write and maintain

4. **Summarize best practices**
   - Best practices for writing documentation
   - Best approaches for AI collaboration
   - Workflow optimization suggestions

## Next Steps

1. Analyze the existing OneClaw project, extract core features
2. Write PRDs for each core feature
3. Write RFCs for each core feature
4. Design test scenarios
5. Have AI implement code based on RFCs
6. Compare new and old implementations

## References

- [Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)
- [Android Architecture Guide](https://developer.android.com/topic/architecture)
- [RFC Process](https://en.wikipedia.org/wiki/Request_for_Comments)
- [Architecture Decision Records](https://adr.github.io/)
