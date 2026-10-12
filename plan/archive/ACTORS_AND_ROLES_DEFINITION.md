---
status: archived
domain: staffing
summary: "Actors and roles definition for staffing module — superseded by STAFFING_MODULE_PLAN.md §4"
---

# Staffing Module Actors and Roles Definition

## Overview
This document defines the actors (users) and their roles within the enhanced staffing RFP/RFQ workflow. While the current implementation only has `staffing-user` and `staffing-admin` roles, a comprehensive staffing module requires more granular role definitions to match real-world responsibilities and enable proper workflow authorization.

## Current Role Limitations
The existing roles are:
- `staffing-user`: Basic access to RFP matching functionality
- `staffing-admin`: Access to module settings and tool configuration

These roles are insufficient for managing a complex staffing workflow because they don't map to specific responsibilities in the RFP lifecycle.

## Proposed Role Structure

### Core Business Roles
These roles represent the functional responsibilities in the staffing process:

1. **Requester** (`requester`)
   - Person who identifies the staffing need and initiates the RFP
   - Typically a hiring manager, department head, or project lead
   - Responsibilities:
     - Create RFPs in DRAFT state
     - Provide detailed requirements
     - Approve/reject candidate submissions (in some models)
     - Provide feedback on interviews

2. **Recruiter** (`recruiter`)
   - Person who manages the sourcing and matching process
   - Could be internal HR recruiter, agency recruiter, or talent acquisition specialist
   - Responsibilities:
     - Move RFPs through SOURCING, SCREENING, MATCHING stages
     - Source candidates from various channels
     - Screen resumes and conduct initial interviews
     - Run matching algorithms and review results
     - Create and manage shortlists
     - Coordinate with Requester and Hiring Manager

3. **Hiring Manager** (`hiring_manager`)
   - Person who has final authority to hire (often the same as Requester but can be different)
   - Responsibilities:
     - Approve RFPs (transition from DRAFT to APPROVED)
     - Review and approve shortlists
     - Participate in interview process
     - Make final hiring decisions
     - Extend job offers (in some models)

4. **Client Liaison** (`client_liaison`)
   - Person who manages the relationship with the client (especially relevant for staffing agencies)
   - Responsibilities:
     - Communicate RFP status to external clients
     - Manage client expectations and feedback
     - Handle contractual and billing aspects
     - Coordinate between internal team and external client

5. **Interview Coordinator** (`interview_coordinator`)
   - Person responsible for scheduling and managing interviews
   - Responsibilities:
     - Schedule interviews between candidates and client team
     - Coordinate interview logistics (video calls, in-person, etc.)
     - Collect and consolidate interview feedback
     - Manage interview feedback deadlines

6. **Offer Manager** (`offer_manager`)
   - Person responsible for managing the job offer process
   - Responsibilities:
     - Extend formal job offers to selected candidates
     - Manage offer negotiations (salary, benefits, start date)
     - Track offer acceptance/rejection
     - Coordinate with HR for offer letter generation

7. **Onboarding Specialist** (`onboarding_specialist`)
   - Person responsible for candidate onboarding post-acceptance
   - Responsibilities:
     - Manage pre-boarding paperwork
     - Coordinate first-day logistics
     - Ensure compliance with legal and company requirements
     - Track onboarding completion

8. **Reporting Analyst** (`reporting_analyst`)
   - Person responsible for generating metrics and insights
   - Responsibilities:
     - Generate workflow analytics (time per stage, conversion rates)
     - Create executive dashboards
     - Identify bottlenecks and improvement opportunities
     - SLA compliance reporting

### Portal-Specific Roles
These roles map to how the Crosshubber portal manages access and permissions:

1. **staffing-requester**
   - Can create RFPs (DRAFT state)
   - Can view and comment on RFPs they created
   - Limited transition abilities (mainly approve/reject based on configuration)

2. **staffing-recruiter**
   - Full access to RFP management functions
   - Can move RFPs through SOURCING → SCREENING → MATCHING → SHORTLISTED → SUBMITTED
   - Can source and manage candidates
   - Can run matching algorithms
   - Can create shortlists

3. **staffing-manager**
   - Combines hiring manager and client liaison functions
   - Can approve RFPs (DRAFT → APPROVED)
   - Can approve shortlists for submission
   - Can move RFPs through client-facing stages (SUBMITTED → INTERVIEWING → etc.)
   - Has oversight capabilities

4. **staffing-coordinator**
   - Specialized role for interview and offer management
   - Can schedule and manage interviews
   - Can extend and manage job offers
   - Can coordinate onboarding

5. **staffing-analyst**
   - Read-only access for reporting and analytics
   - Can view all RFPs and metrics
   - Cannot modify RFP states or data

6. **staffing-admin** (existing)
   - Module configuration and settings management
   - User/role management (within the module)
   - System maintenance functions

## Role-to-Workflow Mapping

### DRAFT Stage
- **Primary Actor**: Requester
- **Portal Role**: staffing-requester
- **Actions**: Create RFP, edit requirements, submit for approval

### APPROVED Stage
- **Primary Actor**: Hiring Manager (approval), Recruiter (initiation of sourcing)
- **Portal Role**: staffing-manager (for approval), staffing-recruiter (to start sourcing)
- **Actions**: Approve RFP, initiate sourcing process

### SOURCING Stage
- **Primary Actor**: Recruiter
- **Portal Role**: staffing-recruiter
- **Actions**: Source candidates, manage candidate pipeline, update sourcing status

### SCREENING Stage
- **Primary Actor**: Recruiter
- **Portal Role**: staffing-recruiter
- **Actions**: Screen resumes, conduct phone screens, track screening results

### MATCHING Stage
- **Primary Actor**: Recruiter (with system assistance)
- **Portal Role**: staffing-recruiter
- **Actions**: Configure matching parameters, run matching algorithms, review results

### SHORTLISTED Stage
- **Primary Actor**: Recruiter, Hiring Manager
- **Portal Role**: staffing-recruiter (create), staffing-manager (approve)
- **Actions**: Create shortlist, review and approve shortlist

### SUBMITTED Stage
- **Primary Actor**: Client Liaison, Recruiter
- **Portal Role**: staffing-manager (client liaison functions), staffing-recruiter
- **Actions**: Submit shortlist to client, track submission status, manage client feedback

### INTERVIEWING Stage
- **Primary Actor**: Interview Coordinator, Hiring Manager
- **Portal Role**: staffing-coordinator (scheduling), staffing-manager (hiring manager functions)
- **Actions**: Schedule interviews, coordinate interview process, collect feedback

### OFFER_EXTENDED Stage
- **Primary Actor**: Offer Manager, Hiring Manager
- **Portal Role**: staffing-coordinator (offer management), staffing-manager (approval)
- **Actions**: Extend offers, manage negotiations, track offer status

### ACCEPTED Stage
- **Primary Actor**: Onboarding Specialist, Recruiter
- **Portal Role**: staffing-coordinator (onboarding), staffing-recruiter (candidate management)
- **Actions**: Initiate onboarding process, track pre-boarding activities

### ONBOARDED Stage
- **Primary Actor**: Onboarding Specialist
- **Portal Role**: staffing-coordinator
- **Actions**: Manage onboarding completion, track start date, ensure compliance

### COMPLETED/CLOSED/ARCHIVED Stages
- **Primary Actor**: Reporting Analyst, any role for archival
- **Portal Role**: staffing-analyst (for reporting), any role (for archival with permissions)
- **Actions**: Generate final reports, analyze outcomes, archive for historical reference

## Role Permissions Matrix

| Role | Create RFP | Edit RFP | Approve RFP | Source Candidates | Run Matching | Create Shortlist | Submit to Client | Schedule Interviews | Extend Offers | Manage Onboarding | View Reports | Admin Settings |
|------|------------|----------|-------------|-------------------|--------------|------------------|------------------|---------------------|---------------|-------------------|--------------|----------------|
| staffing-requester | ✓ | ✓ (own) | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ (own) | ✗ |
| staffing-recruiter | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ (candidate view) | ✓ | ✗ |
| staffing-manager | ✗ | ✗ | ✓ | ✓ (overview) | ✓ (overview) | ✓ (approve) | ✓ | ✓ (overview) | ✓ (approve) | ✗ | ✓ | ✗ |
| staffing-coordinator | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✗ |
| staffing-analyst | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ |
| staffing-admin | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ |

## Implementation Considerations

### Data Model Changes
1. May need to add `createdBy` and `updatedBy` fields to track specific roles (beyond just user ID)
2. Consider adding `assignee` or `owner` fields to RFP entity for stage-specific responsibility
3. Could add a `workflow_notes` or `stage_history` field to track role transitions

### API Changes
1. Update authorization checks in controllers to recognize new roles
2. Consider adding endpoints for role-specific actions (e.g., `/api/rfps/{id}/approve`)
3. Update `AgentPrincipals` to include role information from Keycloak/Groups

### UI Changes
1. Show role-specific actions based on user's portal roles
2. Implement role-based UI components (e.g., only show "Approve" button to users with approval rights)
3. Consider adding a responsibility matrix view in RFP details

### Backend Logic
1. Update `StaffingService` methods to check role-based permissions
2. Consider adding a `WorkflowAuthorizationService` for complex permission checks
3. Update validation logic to enforce role-appropriate transitions

### Integration with Keycloak
1. Map portal roles to Keycloak roles/groups
2. Ensure role information flows properly through the agent-call token mechanism
3. Consider using Keycloak groups for more flexible role management

## Benefits of Defined Roles
1. Clear accountability for each stage of the workflow
2. Proper segregation of duties (e.g., requester shouldn't be able to approve their own RFP without oversight)
3. Better metrics tracking (can measure performance by role)
4. Enhanced security through least-privilege access
5. Improved user experience (users only see relevant actions)
6. Foundation for workflow automation and escalation rules

## Next Steps
1. Design notification schema for workflow automation
2. Specify events to be consumed and published for integration
3. Expand admin settings to cover workflow, notifications, and integrations
4. Enhance agentic tools for workflow automation and external integrations
5. Identify required dependencies (CV/people DB, CRM, HRIS) and integration approaches