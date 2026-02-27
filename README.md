# OneClaw Shadow

> A documentation-driven AI-assisted Android application development project

## Project Philosophy

This is an experimental project exploring a new development paradigm in the AI era:

1. **Documentation as the Single Source of Truth** - Code can be rewritten, but requirements and design documents are persistent
2. **Pure Documentation-Driven AI Development** - AI generates code solely based on PRD and RFC, without referencing existing implementations
3. **Automated Verification** - Comprehensive testing system ensures quality and reduces manual bottlenecks
4. **Reproducibility** - The entire application can be regenerated from documentation at any time

## Project Structure

```
oneclaw-shadow-1/
├── docs/                  # Documentation Center (Core)
│   ├── prd/              # Product Requirements Documents
│   ├── rfc/              # Technical Design Documents
│   ├── adr/              # Architecture Decision Records
│   └── testing/          # Testing Strategy Documents
├── tests/                # Testing Center
│   ├── plans/           # Test Plans
│   ├── scenarios/       # Test Scenarios (YAML format)
│   ├── unit/           # Unit Tests
│   ├── integration/    # Integration Tests
│   └── e2e/            # End-to-End Tests
├── app/                 # Android Application Code
├── scripts/             # Automation Scripts
└── tools/              # Development Tools
```

## Development Workflow

```
1. Requirements Phase
   └── Write/Update PRD → AI-assisted refinement → Review and approve

2. Design Phase
   └── Write RFC based on PRD → AI provides technical suggestions → Review and approve

3. Development Phase
   └── AI generates code from RFC documentation → Review critical architecture points

4. Testing Phase
   └── Write test scenarios → AI generates test code → Automated execution

5. Iteration Phase
   └── Modify PRD/RFC → AI regenerates code → Automated verification
```

## Core Principles

### 1. Documentation-Driven
- Every feature must have PRD first, then RFC, and finally code
- PRD describes "what to do and why"
- RFC describes "how to do it"
- Code is an implementation of documentation

### 2. Traceability
- Unified ID system:
  - `FEAT-001`: Feature PRD
  - `RFC-001`: Technical Design
  - `ADR-001`: Architecture Decision
  - `TEST-001`: Test Scenario

### 3. Automation First
- Tests described in structured format (YAML)
- CI/CD automatically runs all tests
- Humans make decisions, not repetitive work

### 4. Pure Documentation-Driven AI Development
- AI doesn't look at existing code
- All implementation details explicitly defined in RFC
- Can completely refactor based on documentation at any time

## Quick Start

### Adding a New Feature

1. Create PRD document: `docs/prd/features/FEAT-XXX-feature-name.md`
2. Create RFC document: `docs/rfc/features/RFC-XXX-feature-name.md`
3. Create test scenario: `tests/scenarios/FEAT-XXX-scenario.yaml`
4. Let AI generate code based on RFC
5. Run tests to verify: `./scripts/run-tests.sh`

### Document Templates

All document templates are located at:
- PRD template: `docs/prd/_template.md`
- RFC template: `docs/rfc/_template.md`
- ADR template: `docs/adr/_template.md`
- Test scenario template: `tests/scenarios/_template.yaml`

## Project Status

Current version: Experimental phase - Establishing documentation and workflow system

## References

- [Complete Design Documentation](docs/00-project-design.md)
- [Development Workflow](docs/01-workflow.md)
- [Testing Strategy](docs/testing/strategy.md)
