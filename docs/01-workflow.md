# Development Workflow Guide

This document provides detailed workflow guidance to help you develop the OneClaw application following a documentation-driven approach.

## Quick Start

### First Time Setup

```bash
# 1. Enter the project directory
cd oneclaw-shadow-1

# 2. Read the project design document
cat docs/00-project-design.md

# 3. Review the PRD overview
cat docs/prd/00-overview.md

# 4. Prepare to start on the first feature
# Use the PRD template
cp docs/prd/_template.md docs/prd/features/FEAT-001-your-feature.md
```

## Workflow in Detail

### Workflow 1: Adding a New Feature

#### Phase 1: Requirements Definition (Manual + AI-Assisted)

**Input**: Feature idea
**Output**: Complete PRD document

**Steps**:

1. **Create PRD Document**
   ```bash
   # Create a new PRD from the template
   cp docs/prd/_template.md docs/prd/features/FEAT-XXX-feature-name.md
   ```

2. **Fill in Basic Information**
   - Feature ID: FEAT-XXX (incrementing sequence number)
   - Feature name: Short and clear name
   - Priority: P0/P1/P2
   - Status: Draft

3. **Write User Stories**
   ```markdown
   As a [user role]
   I want to [specific feature]
   So that [goal achieved]
   ```
   
   Tips:
   - Think from the user's perspective
   - Focus on value, not implementation
   - One user story solves one problem

4. **Write Feature Description**
   - Summarize the feature in 1-2 paragraphs
   - List feature points in detail
   - Describe user interaction flows
   
   Tips:
   - Use "The user can..." phrasing
   - Avoid technical jargon
   - Simple flowcharts may be included

5. **Define Acceptance Criteria**
   ```markdown
   - [ ] User can complete YY via XX method
   - [ ] When condition ZZ is met, the system should AA
   - [ ] The interface displays BB information
   ```
   
   Tips:
   - Each criterion must be verifiable
   - Use specific, observable behaviors
   - Include both normal and exceptional cases

6. **Describe UI/UX Requirements**
   - Interface layout
   - Interaction patterns
   - Visual style
   - (If design mockups exist) Add links
   
7. **Define Feature Boundaries**
   ```markdown
   Included:
   - Feature point A
   - Feature point B
   
   Not included:
   - Feature point C (deferred to next version)
   - Feature point D (out of product scope)
   ```

8. **(Optional) AI-Assisted Refinement**
   - Give your PRD draft to AI
   - Have AI help:
     - Discover missed scenarios
     - Add edge cases
     - Refine acceptance criteria
     - Check logical consistency

9. **Review and Approve**
   - Self-review once
   - Confirm requirements are clear and unambiguous
   - Update status to "Approved"

**Checklist**:
- [ ] Feature ID assigned
- [ ] User stories are clear
- [ ] Acceptance criteria are complete and verifiable
- [ ] Feature boundaries are clear
- [ ] UI/UX requirements are clear
- [ ] Dependencies are noted

---

#### Phase 2: Technical Design (Manual + AI-Assisted)

**Input**: Approved PRD
**Output**: Complete RFC document

**Steps**:

1. **Create RFC Document**
   ```bash
   cp docs/rfc/_template.md docs/rfc/features/RFC-XXX-feature-name.md
   ```

2. **Fill in Basic Information**
   - RFC number: RFC-XXX (corresponding to FEAT number or independently numbered)
   - Associated PRD: FEAT-XXX
   - Status: Draft

3. **Design Overall Architecture**
   - Draw architecture diagrams (ASCII or descriptive)
   - List core components
   - Explain relationships between components
   
   Example:
   ```
   UI Layer: LoginScreen + LoginViewModel
   Domain Layer: LoginUseCase
   Data Layer: AuthRepository + RemoteDataSource
   ```

4. **Design Data Models**
   ```kotlin
   // Define all data classes in detail
   data class User(
       val id: String,
       val email: String,
       val name: String,
       val createdAt: Long
   )
   ```
   
   Tips:
   - Include all fields and types
   - Add comments explaining field meanings
   - Consider optional fields and default values

5. **Design API Interfaces**
   
   Internal APIs (within the app):
   ```kotlin
   interface AuthRepository {
       suspend fun login(email: String, password: String): Result<User>
       suspend fun logout(): Result<Unit>
   }
   ```
   
   External APIs (backend):
   ```
   POST /api/auth/login
   Request: { "email": "...", "password": "..." }
   Response: { "token": "...", "user": {...} }
   ```
   
   Tips:
   - Define complete request and response formats
   - Include error responses
   - Specify authentication methods

6. **Design UI Layer**
   ```kotlin
   // Define UiState
   data class LoginUiState(
       val email: String = "",
       val password: String = "",
       val isLoading: Boolean = false,
       val error: String? = null
   )
   
   // Define ViewModel
   class LoginViewModel : ViewModel() {
       // Detailed implementation logic
   }
   ```

7. **Plan Implementation Steps**
   ```markdown
   Phase 1: Data Layer
   1. [ ] Create User data class
   2. [ ] Create AuthRepository interface
   3. [ ] Implement RemoteDataSource
   4. [ ] Implement AuthRepositoryImpl
   
   Phase 2: Domain Layer
   1. [ ] Create LoginUseCase
   
   Phase 3: UI Layer
   1. [ ] Create LoginUiState
   2. [ ] Create LoginViewModel
   3. [ ] Create LoginScreen
   ```

8. **Define Error Handling Strategy**
   ```kotlin
   sealed class Result<out T> {
       data class Success<T>(val data: T) : Result<T>()
       data class Error(val exception: Exception) : Result<Nothing>()
   }
   ```

9. **Consider Non-Functional Requirements**
   - Performance: Response time, concurrency handling
   - Security: Data encryption, access control
   - Testability: How to mock dependencies

10. **(Optional) AI-Assisted Design**
    - Show AI the PRD and initial RFC
    - Have AI:
      - Suggest technical approaches
      - Identify potential issues
      - Optimize data structures
      - Fill in implementation details

11. **Review and Approve**
    - Check design completeness
    - Confirm AI can implement directly from it
    - Update status to "Approved"

**Checklist**:
- [ ] RFC number assigned
- [ ] Associated PRD noted
- [ ] Architecture design is clear
- [ ] Data models are complete
- [ ] API design is explicit
- [ ] UI layer design is detailed
- [ ] Implementation steps are clear
- [ ] Error handling is thorough
- [ ] Technology choices are justified

---

#### Phase 3: Test Design (Manual)

**Input**: Approved PRD and RFC
**Output**: Test scenario YAML files

**Steps**:

1. **Create Test Scenario File**
   ```bash
   cp tests/scenarios/_template.yaml tests/scenarios/TEST-XXX-scenario-name.yaml
   ```

2. **Fill in Metadata**
   ```yaml
   metadata:
     scenario_id: TEST-XXX
     feature_id: FEAT-XXX
     rfc_id: RFC-XXX
     name: "User Login Flow"
     type: e2e  # unit/integration/e2e
     priority: P0
   ```

3. **Define Test Data**
   ```yaml
   test_data:
     valid_user:
       email: "test@example.com"
       password: "Test123456"
     invalid_user:
       email: "wrong@example.com"
       password: "wrongpass"
   ```

4. **Write Main Scenario (Happy Path)**
   ```yaml
   scenarios:
     - name: "Normal Login Flow"
       steps:
         - step_id: 1
           action: "Open application"
           expected:
             - "Login screen is displayed"
           verification:
             - type: "screen_visible"
               target: "LoginScreen"
         
         - step_id: 2
           action: "Enter username and password"
           data: "${test_data.valid_user}"
           expected:
             - "Input fields display the entered content"
         
         - step_id: 3
           action: "Tap the login button"
           expected:
             - "Successfully navigates to the home page"
           verification:
             - type: "screen_visible"
               target: "HomeScreen"
   ```

5. **Write Exception Scenarios**
   - Invalid input
   - Network errors
   - Boundary conditions
   - Concurrency situations
   
   Each acceptance criterion in the PRD should correspond to at least one test scenario

6. **Define Performance Criteria**
   ```yaml
   performance_criteria:
     - metric: "login_response_time"
       threshold: 2000  # ms
   ```

7. **Review Test Scenarios**
   - Do they cover all acceptance criteria
   - Do they include exceptional cases
   - Can they be automated

**Checklist**:
- [ ] Test scenario ID assigned
- [ ] Associated FEAT and RFC linked
- [ ] Test data is complete
- [ ] Main flow is covered
- [ ] Exception cases are covered
- [ ] Verification methods are clear
- [ ] Performance criteria are defined

---

#### Phase 4: AI Development Implementation

**Input**: Approved RFC
**Output**: Implementation code

**Steps**:

1. **Prepare AI Prompt**
   ```
   I need you to implement code based on this RFC document.
   
   Important constraints:
   - Implement only based on the RFC, do not reference any existing code
   - Strictly follow the data models and interface definitions in the RFC
   - Use the tech stack specified in the RFC
   - Follow Clean Architecture + MVVM
   
   RFC document:
   [Paste the complete RFC-XXX content]
   
   Please implement the code step by step following the implementation steps in the RFC.
   ```

2. **Have AI Implement in Phases**
   
   Do not ask AI to implement all code at once. Instead:
   
   ```
   Phase 1: Implement the data layer first
   Please implement:
   - User data class
   - AuthRepository interface
   - RemoteDataSource
   - AuthRepositoryImpl
   ```
   
   After reviewing the code, continue:
   
   ```
   Phase 2: Implement the domain layer
   Please implement:
   - LoginUseCase
   ```
   
   Finally:
   
   ```
   Phase 3: Implement the UI layer
   Please implement:
   - LoginUiState
   - LoginViewModel
   - LoginScreen
   ```

3. **Review Generated Code**
   
   Key checks:
   - [ ] Does it follow the RFC design
   - [ ] Does it use the correct architectural layering
   - [ ] Does it have proper error handling
   - [ ] Does it have necessary comments
   - [ ] Is the code style consistent

4. **Run the Code**
   ```bash
   ./gradlew build
   ./gradlew installDebug
   ```

5. **If There Are Issues**
   - Do not directly ask AI to fix the code
   - Instead, go back to the RFC and check if it needs updating
   - After updating the RFC, regenerate the code

---

#### Phase 5: AI-Generated Test Code

**Input**: Test scenario YAML + implementation code
**Output**: Automated test code

**Steps**:

1. **Have AI Generate Unit Tests**
   ```
   Please generate unit test code based on the following test scenarios:
   
   [Paste TEST-XXX.yaml content]
   
   Requirements:
   - Use JUnit 5
   - Use MockK for mocking
   - Test ViewModels and UseCases
   - Cover both normal and exceptional cases
   ```

2. **Have AI Generate Integration Tests**
   ```
   Please generate integration tests to test data layer functionality:
   - Use Room's testing tools
   - Use MockWebServer to test API calls
   ```

3. **Have AI Generate UI Tests**
   ```
   Please generate UI tests based on the test scenarios:
   - Use Jetpack Compose Test
   - Test complete user flows
   ```

4. **Review Test Code**
   - [ ] Do tests cover all scenarios
   - [ ] Are assertions correct
   - [ ] Are mocks reasonable

5. **Run Tests**
   ```bash
   # Unit tests
   ./gradlew test
   
   # Integration tests
   ./gradlew connectedAndroidTest
   
   # Check coverage
   ./gradlew jacocoTestReport
   open app/build/reports/jacoco/html/index.html
   ```

---

#### Phase 6: Verification and Delivery

**Steps**:

1. **Run All Tests**
   ```bash
   ./scripts/run-tests.sh
   ```

2. **Check Test Coverage**
   - Target: > 80%
   - Focus on covering business logic

3. **Manually Verify Critical Flows**
   - Although automated tests exist, manually test critical flows once
   - Primarily check if UI/UX meets expectations

4. **Update Document Status**
   - PRD status -> "Completed"
   - RFC status -> "Completed"
   - Add completion date

5. **Commit Code**
   ```bash
   git add .
   git commit -m "feat: implement XXX feature (FEAT-XXX)"
   git push
   ```

---

### Workflow 2: Modifying an Existing Feature

When requirements change or issues are discovered:

1. **Update PRD**
   - Modify feature description or acceptance criteria
   - Record the changes in the change history
   - Update the version number

2. **Update RFC**
   - Adjust the technical approach based on PRD changes
   - Record the changes in the change history

3. **Update Test Scenarios**
   - Add new test scenarios
   - Modify existing scenarios

4. **Have AI Regenerate Code**
   - Give AI the new RFC
   - Explicitly tell AI: re-implement this feature
   - Do not make incremental changes; regenerate instead

5. **Run Tests to Verify**
   - All existing tests should still pass
   - New test scenarios should pass

6. **Compare New and Old Implementations**
   ```bash
   # If comparison is needed
   git diff old-branch new-branch -- path/to/feature
   ```

---

### Workflow 3: Refactoring the Entire Application

This is the scenario of most interest: completely rewriting the application based on documentation.

**Scenario**: Existing code has too much technical debt, or you want to switch tech stacks.

**Steps**:

1. **Ensure Documentation is Complete**
   ```bash
   # Check that all features have PRDs and RFCs
   ls docs/prd/features/
   ls docs/rfc/features/
   ```
   
   If a feature is missing documentation:
   - Write the PRD and RFC first
   - Can reverse-engineer from existing code

2. **Create a New Implementation Branch or Directory**
   ```bash
   # Option A: New branch
   git checkout -b refactor-v2
   
   # Option B: New directory (recommended for experimentation)
   mkdir app-v2
   ```

3. **Prepare AI Prompt**
   ```
   I need you to implement an Android application from scratch based on a series of RFC documents.
   
   Important constraints:
   - Do not reference any existing code
   - Implement strictly according to the RFCs
   - Use the latest tech stack and best practices
   
   Project structure:
   [Paste the target directory structure]
   
   Tech stack:
   - Kotlin 1.9.x
   - Jetpack Compose
   - Clean Architecture + MVVM
   - [Other technologies]
   
   I will provide the RFC for each feature step by step. Please implement them in order.
   ```

4. **Implement Module by Module**
   
   ```
   Step 1: Implement infrastructure
   RFC: docs/rfc/architecture/001-overall-architecture.md
   Please implement:
   - Project structure
   - Dependency injection configuration
   - Network layer configuration
   - Database configuration
   ```
   
   ```
   Step 2: Implement authentication module
   RFC: docs/rfc/features/RFC-001-auth-implementation.md
   Please implement the complete authentication module
   ```
   
   ```
   Step 3: Implement cloud storage module
   RFC: docs/rfc/features/RFC-002-cloud-storage-implementation.md
   Please implement the complete cloud storage module
   ```
   
   And so on...

5. **Test Immediately After Each Module is Implemented**
   ```bash
   # Generate tests for this module
   # Run tests
   ./gradlew test
   ```

6. **Run Full Test Suite After All Modules Are Complete**
   ```bash
   ./scripts/run-tests.sh
   ```

7. **Compare New and Old Implementations**
   
   Create a comparison report:
   ```markdown
   # Refactoring Comparison Report
   
   ## Code Quality
   - Old version: XXX lines of code, YYY files
   - New version: AAA lines of code, BBB files
   - Code reduction/increase: ZZ%
   
   ## Test Coverage
   - Old version: XX%
   - New version: YY%
   
   ## Performance Comparison
   - Startup time: Old XXms vs New YYms
   - Memory usage: Old XXmb vs New YYmb
   
   ## Technical Debt
   - Old version: List main issues
   - New version: Resolved issues
   
   ## Conclusion
   Adopt new implementation: [ ] Yes [ ] No
   Reason: ...
   ```

8. **Decision**
   - If the new implementation is better: Merge into the main branch
   - If there are issues: Modify RFCs, regenerate parts of the code
   - Keep the old implementation as reference

---

## AI Collaboration Tips

### Best Prompt Templates for AI

#### Implementing Features
```
I need you to implement code based on this RFC.

Constraints:
- Implement only based on the RFC, do not reference existing code
- Strictly follow the design in the RFC
- Use the tech stack specified in the RFC
- Follow Clean Architecture

RFC content:
---
[Paste complete RFC]
---

Please implement the following parts:
1. [Specific component to implement]
2. [Specific component to implement]

For each component, please:
- Implement completely, do not omit anything
- Add necessary comments
- Include error handling
```

#### Generating Tests
```
Please generate automated test code based on these test scenarios.

Test scenarios:
---
[Paste YAML content]
---

Requirements:
- Use JUnit 5 / Jetpack Compose Test
- Use MockK for mocking
- Cover all scenarios
- Add clear comments

Code under test:
---
[Paste relevant code]
---
```

#### Refining PRDs
```
I have written a PRD draft. Please help me:
1. Discover missed scenarios
2. Add edge cases
3. Refine acceptance criteria
4. Identify unclear areas

PRD content:
---
[Paste PRD]
---

Please maintain a product perspective and do not involve technical implementation.
```

#### Designing RFCs
```
I have a PRD. Please help me design the technical approach.

PRD content:
---
[Paste PRD]
---

Tech stack:
- Kotlin + Jetpack Compose
- Clean Architecture + MVVM
- Room + Retrofit

Please provide:
1. Architecture design
2. Data models
3. API design
4. Implementation steps

The RFC should be detailed enough to be used directly for implementation.
```

### FAQ

**Q: AI-generated code doesn't match the RFC?**
A:
1. Check if the RFC is detailed enough
2. Emphasize "strictly follow the RFC" in the prompt
3. Paste code examples from the RFC directly for AI reference

**Q: AI keeps referencing existing code?**
A:
1. Explicitly tell AI "do not look at existing code"
2. Do not mention existing code in the conversation
3. Only provide the RFC document

**Q: Test coverage is insufficient?**
A:
1. Add more cases to the test scenarios
2. Have AI generate tests for each public method
3. Use coverage reports to identify uncovered code

**Q: Tests fail after refactoring?**
A:
1. Check if the RFC is consistent with the PRD
2. Check if test scenarios need updating
3. Do not modify tests to fit the code; modify the code to fit the tests

---

## Tool Usage

### Validate Documentation Completeness
```bash
python tools/validate-docs.py
```

Checks:
- All FEATs have corresponding RFCs
- All RFCs have corresponding TESTs
- Document format is correct
- IDs have no conflicts

### Generate Documentation Dependency Graph
```bash
python tools/doc-graph.py
```

Generates something like:
```
FEAT-001 (User Authentication)
  ├─ RFC-001 (Auth Implementation)
  │   └─ ADR-001 (Chose JWT)
  └─ TEST-001 (Login Flow)
  └─ TEST-002 (Logout Flow)

FEAT-002 (Cloud Storage)
  ├─ RFC-002 (Storage Implementation)
  └─ TEST-003 (File Upload)
  └─ TEST-004 (File Download)
```

### Run All Tests
```bash
./scripts/run-tests.sh

# Output:
# - Unit tests: 45/45 passed
# - Integration tests: 12/12 passed
# - UI tests: 8/8 passed
# - Coverage: 87%
```

---

## Next Steps

Based on your situation, here are recommendations:

1. **If starting from scratch**
   - Start with the most critical feature
   - Write the first PRD (e.g., user authentication)
   - Write the corresponding RFC
   - Have AI implement it
   - Establish the workflow

2. **If refactoring an existing application**
   - List all core features first
   - Reverse-engineer PRDs and RFCs for each feature
   - Re-implement using the new workflow
   - Compare new and old versions

Where would you like to start?
