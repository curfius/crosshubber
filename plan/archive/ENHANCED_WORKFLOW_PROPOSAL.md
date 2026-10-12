---
status: archived
domain: staffing
summary: "Enhanced workflow proposal — superseded by STAFFING_MODULE_PLAN.md §3"
---

# Enhanced Staffing RFP/RFQ Workflow Proposal

## Overview
This document proposes an enhanced workflow for managing staffing RFPs (Request for Proposals) and RFQs (Request for Quotes) within the Crosshubber portal staffing module. The current implementation provides basic RFP lifecycle management but lacks the sophistication needed for comprehensive staffing agency operations.

## Current Workflow Limitations
The existing workflow is linear and simplistic:
```
NEW → ANALYZING → MATCHED → SHORTLISTED → SUBMITTED → ARCHIVED
```

Key limitations:
1. No distinction between RFP and RFQ workflows
2. Missing key stages like approval, sourcing, screening, interviewing
3. No ability to iterate between stages (e.g., restart sourcing if no suitable candidates)
4. No explicit handling of offer management and onboarding
5. Limited ability to reflect real-world staffing agency processes

## Proposed Enhanced Workflow

### Status Definitions
```
DRAFT: Initial creation, requirement gathering in progress
APPROVED: Request approved, ready for sourcing
SOURCING: Actively searching for candidates (internal DB, job boards, referrals)
SCREENING: Reviewing resumes, conducting initial phone screens
MATCHING: Running automated matching algorithms against requirements
SHORTLISTED: Selected candidates prepared for client submission
SUBMITTED: Shortlist submitted to client for review
INTERVIEWING: Client conducting interviews (phone, technical, etc.)
OFFER_EXTENDED: Formal offer made to selected candidate
ACCEPTED: Candidate has accepted the offer
ONBOARDED: Candidate completed onboarding, start date confirmed
COMPLETED: Placement successfully completed (end of guarantee period)
CLOSED: Position filled through other means or requirement cancelled
ARCHIVED: Historical record (reachable from most states)
```

### State Transition Diagram
```
DRAFT
  ↓ (approve)
APPROVED
  ↓ (source)
SOURCING
  ↓ (screen)
SCREENING
  ↓ (match)
MATCHING
  ↓ (shortlist)
SHORTLISTED
  ↓ (submit)
SUBMITTED
  ↓ (interview)
INTERVIEWING
  ↓ (offer)
OFFER_EXTENDED
  ↓ (accept)
ACCEPTED
  ↓ (onboard)
ONBOARDED
  ↓ (complete)
COMPLETED

# Alternative paths and iterations
APPROVED ←→ SOURCING (req changes)
SOURCING ←→ SCREENING (need more candidates)
SCREENING ←→ MATCHING (adjust criteria)
MATCHING ←→ SHORTLISTED (refine selection)
SHORTLISTED ←→ SUBMITTED (client feedback)
SUBMITTED ←→ INTERVIEWING (schedule issues)
INTERVIEWING ←→ SOURCING (no suitable interviewees)
OFFER_EXTENDED ←→ INTERVIEWING (negotiation failure)
OFFER_EXTENDED ←→ ACCEPTED (rejection/negotiation)
ACCEPTED ←→ OFFER_EXTENDED (counter-offer)
CLOSED → ARCHIVED
Any state → ARCHIVED (manual archive)
```

### Transition Rules
1. **DRAFT** can transition to: APPROVED, ARCHIVED
2. **APPROVED** can transition to: SOURCING, DRAFT (for edits), ARCHIVED
3. **SOURCING** can transition to: SCREENING, APPROVED (req changes), ARCHIVED
4. **SCREENING** can transition to: MATCHING, SOURCING (need more), ARCHIVED
5. **MATCHING** can transition to: SHORTLISTED, SCREENING (adjust criteria), ARCHIVED
6. **SHORTLISTED** can transition to: SUBMITTED, MATCHING (refine), ARCHIVED
7. **SUBMITTED** can transition to: INTERVIEWING, SHORTLISTED (revise), ARCHIVED
8. **INTERVIEWING** can transition to: OFFER_EXTENDED, SOURCING (no fits), ARCHIVED
9. **OFFER_EXTENDED** can transition to: ACCEPTED, INTERVIEWING (rejection), ARCHIVED
10. **ACCEPTED** can transition to: ONBOARDED, OFFER_EXTENDED (negotiation), ARCHIVED
11. **ONBOARDED** can transition to: COMPLETED, ARCHIVED
12. **COMPLETED** is terminal (can only go to ARCHIVED)
13. **CLOSED** is terminal (can only go to ARCHIVED)
14. **ARCHIVED** is terminal

### Special Considerations
1. **RFQ vs RFP Differentiation**: 
   - RFQs might skip some stages (e.g., less emphasis on lengthy screening)
   - Could have different default weights in matching algorithms
   - Different SLA expectations

2. **Parallel Processes**:
   - While Matching is running, Sourcing can continue for backup candidates
   - Interview scheduling can happen while final matches are being reviewed

3. **Iteration Handling**:
   - Clear paths to return to previous stages when needed
   - Ability to add notes/documentation when backtracking

4. **Terminal States**:
   - COMPLETED: Successful placement
   - CLOSED: Position filled another way or req cancelled
   - ARCHIVED: Historical record (reachable from any state)

## Implementation Implications

### Data Model Changes
1. Expand `RfpStatus` enum with new states
2. Update `TRANSITIONS` map in `RfpStatus` enum
3. Consider adding fields for tracking:
   - Actual start/end dates per stage
   - Responsible owners per stage
   - Stage-specific metrics

### API Changes
1. Update `TransitionRequest` in `RfpController` to handle new statuses
2. Ensure validation logic accommodates complex transitions
3. Consider adding endpoints for stage-specific operations

### UI Changes
1. Update status badges and filters in RFP list component
2. Enhance RFP detail to show stage-specific information
3. Add stage transition buttons with appropriate validation
4. Consider adding a workflow visualization component

### Backend Logic
1. Update `StaffingService.transition()` method to handle new logic
2. Consider adding stage-specific service methods
3. Update any hardcoded status references throughout the codebase

## Benefits of Enhanced Workflow
1. Better reflects real-world staffing agency processes
2. Provides clearer visibility into where RFPs are stuck
3. Enables more precise metrics and reporting (time per stage, conversion rates)
4. Supports better resource allocation (knowing which stage needs attention)
5. Facilitates integration with external systems (CRM, HRIS) at appropriate stages
6. Lays foundation for workflow automation and agentic tools

## Next Steps
1. Define actors/roles and their responsibilities in each stage
2. Design notification schema for stage transitions
3. Specify events for integration with external systems
4. Expand admin settings to configure workflow behavior
5. Enhance agentic tools for workflow automation