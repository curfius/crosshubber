---
status: archived
domain: messaging
summary: "Notification and task schema for staffing workflow automation — superseded by STAFFING_MODULE_PLAN.md §10 (msgcenter reuse)"
---

# Notification and Task Schema for Staffing Workflow Automation

## Overview
This document defines the notification and task schema for automating the staffing RFP/RFQ workflow. Effective workflow automation requires timely notifications to the right people and clear task assignments to ensure work progresses smoothly through the various stages.

## Current State
The current staffing module has no built-in notification or task management system. Users must manually check the portal for updates and rely on external communication methods (email, chat, etc.) for coordination.

## Notification Schema Design

### Notification Types
Notifications are automated messages triggered by specific events in the workflow. Each notification type has:

1. **Trigger Event**: What causes the notification to be sent
2. **Recipients**: Who should receive the notification (based on roles, assignments, etc.)
3. **Template**: The message content (can be customized per tenant)
4. **Channel**: How the notification is delivered (email, in-app, SMS, etc.)
5. **Priority**: Urgency level (low, medium, high, urgent)
6. **Timing**: Whether it's sent immediately, after a delay, or at a scheduled time

### Core Notification Types

#### 1. RFP Creation Notifications
- **Trigger**: RFP created in DRAFT state
- **Recipients**: Assigned Recruiter, Requester (for confirmation)
- **Purpose**: Inform team that a new RFP requires attention
- **Template**: "New RFP '[title]' created by [requester] for [client]. Requires requirement finalization."
- **Channel**: Email + In-app notification
- **Priority**: Medium

#### 2. Approval Required Notifications
- **Trigger**: RFP transitioned to PENDING_APPROVAL or requires approval
- **Recipients**: Assigned Approver/Hiring Manager
- **Purpose**: Notify approver that a decision is needed
- **Template**: "RFP '[title]' for [client] requires your approval. Deadline: [date]"
- **Channel**: Email + In-app notification
- **Priority**: High
- **Escalation**: If not acted upon in 24 hours, notify backup approver

#### 3. Stage Transition Notifications
- **Trigger**: RFP moves to a new stage (SOURCING, SCREENING, etc.)
- **Recipients**: Role responsible for the new stage
- **Purpose**: Inform responsible party that work can begin
- **Template**: "RFP '[title]' has moved to [STAGE] stage. [Action Required]"
- **Channel**: In-app notification (primary), Email (secondary)
- **Priority**: Medium

#### 4. Action Required Notifications
- **Trigger**: Specific action needed from a user (e.g., "review shortlist", "provide interview feedback")
- **Recipients**: Specific user or role assigned to the task
- **Purpose**: Direct notification of a specific task
- **Template**: "[User], please [action] for RFP '[title]' by [deadline]"
- **Channel**: Email + In-app notification
- **Priority**: High
- **Tracking**: Should create a task in the task management system

#### 5. SLA Warning Notifications
- **Trigger**: Time in current stage approaching or exceeding SLA threshold
- **Recipients**: Current role owner, their manager (if escalating)
- **Purpose**: Warn of potential delays
- **Template**: "RFP '[title]' has been in [STAGE] stage for [time]. SLA threshold: [threshold]"
- **Channel**: Email + In-app notification
- **Priority**: High (warning), Urgent (overdue)
- **Escalation**: Progressive notifications as time passes

#### 6. Completion/Milestone Notifications
- **Trigger**: Major milestone reached (shortlist submitted, offer accepted, etc.)
- **Recipients**: Requester, Hiring Manager, Client Liaison, Recruiter
- **Purpose**: Inform stakeholders of progress
- **Template**: "Milestone reached: [milestone] for RFP '[title]'"
- **Channel**: Email + In-app notification
- **Priority**: Medium

#### 7. Blocked/Issue Notifications
- **Trigger**: Workflow blocked (e.g., no candidates found, client unresponsive)
- **Recipients**: Role owner, their manager, Requester
- **Purpose**: Alert to problems requiring intervention
- **Template**: "Issue detected: [description] for RFP '[title]'"
- **Channel**: Email + In-app notification + SMS (if urgent)
- **Priority**: High (issue), Urgent (blocked)
- **Requires**: Acknowledgment and resolution tracking

#### 8. Periodic Summary Notifications
- **Trigger**: Scheduled (daily, weekly)
- **Recipients**: Managers, executives
- **Purpose**: Provide overview of workflow status
- **Template**: Weekly summary: [X] RFPs in sourcing, [Y] in interviewing, [Z] offers extended, etc.
- **Channel**: Email
- **Priority**: Low
- **Timing**: Monday 8 AM, daily 4 PM

## Task Schema Design

Tasks represent specific units of work that need to be completed within the workflow. Unlike notifications (which are informational), tasks require action and completion tracking.

### Task Characteristics
Each task has:
1. **Title**: Brief description of the work
2. **Description**: Detailed instructions
3. **Assignee**: Person responsible for completion
4. **Due Date**: When the task should be completed
5. **Priority**: Urgency level
6. **Status**: To-do, In Progress, Blocked, Completed
7. **Related Entity**: Which RFP/Candidate/etc. the task relates to
8. **Task Type**: Category of work (sourcing, screening, scheduling, etc.)
9. **Dependencies**: Other tasks that must be completed first
10. **Estimated Effort**: Time estimation for planning

### Core Task Types

#### 1. Sourcing Tasks
- **Title**: "Source candidates for [skill set] roles"
- **Description**: "Find [number] candidates with [skills] for [location] RFP"
- **Assignee**: Recruiter
- **Related To**: RFP
- **Task Type**: sourcing
- **Typical Duration**: 2-5 days
- **Dependencies**: RFP approved

#### 2. Screening Tasks
- **Title**: "Screen [number] resumes for [RFP title]"
- **Description": "Review resumes and conduct initial phone screens"
- **Assignee**: Recruiter
- **Related To**: RFP
- **Task Type**: screening
- **Typical Duration**: 1-3 days
- **Dependencies**: Sourcing task completion or candidate submission

#### 3. Matching Tasks
- **Title**: "Run matching analysis for [RFP title]"
- **Description": "Execute matching algorithms and review top candidates"
- **Assignee**: Recruiter
- **Related To**: RFP
- **Task Type**: matching
- **Typical Duration**: 0.5 days (mostly automated)
- **Dependencies**: Screening completion

#### 4. Shortlist Creation Tasks
- **Title**: "Create shortlist for [RFP title]"
- **Description": "Select top candidates and prepare submission package"
- **Assignee**: Recruiter
- **Related To**: RFP
- **Task Type**: shortlist_creation
- **Typical Duration**: 1 day
- **Dependencies**: Matching completion

#### 5. Interview Scheduling Tasks
- **Title**: "Schedule interviews for [RFP title] shortlist"
- **Description": "Coordinate interview times between client team and candidates"
- **Assignee**: Interview Coordinator
- **Related To**: RFP
- **Task Type**: interview_scheduling
- **Typical Duration**: 1-2 days
- **Dependencies**: Shortlist submitted/approved

#### 6. Interview Feedback Tasks
- **Title**: "Collect interview feedback for [RFP title]"
- **Description": "Gather feedback from all interviewers within 24 hours of interview"
- **Assignee": Interview Coordinator
- **Related To": Specific interview instance
- **Task Type": interview_feedback
- **Typical Duration": 1 day
- **Dependencies": Interview completion

#### 7. Offer Preparation Tasks
- **Title**: "Prepare job offer for [candidate name]"
- **Description": "Draft offer letter with compensation, benefits, start date"
- **Assignee": Offer Manager
- **Related To": RFP + Specific candidate
- **Task Type": offer_preparation
- **Typical Duration": 1 day
- **Dependencies": Positive interview feedback

#### 8. Offer Extension Tasks
- **Title**: "Extend job offer to [candidate name]"
- **Description": "Formally present offer and manage initial response"
- **Assignee": Offer Manager
- **Related To": RFP + Specific candidate
- **Task Type": offer_extension
- **Typical Duration": 2-3 days (includes negotiation period)
- **Dependencies": Offer preparation

#### 9. Onboarding Tasks
- **Title**: "Onboard [candidate name] for [position]"
- **Description": "Complete pre-boarding paperwork and first-day arrangements"
- **Assignee": Onboarding Specialist
- **Related To": RFP + Specific candidate (accepted offer)
- **Task Type": onboarding
- **Typical Duration": 3-5 days
- **Dependencies": Offer accepted

### Task Lifecycle
1. **Created**: Task is defined and assigned
2. **Assigned**: Task has been assigned to a user
3. **In Progress**: User has started working on the task
4. **Blocked**: Task cannot proceed due to external dependency
5. **Completed**: Task has been finished
6. **Cancelled**: Task is no longer needed

### Task Management Features
1. **Assignment**: Manual assignment or automatic based on role/workflow stage
2. **Prioritization**: Automatic prioritization based on RFP priority, SLA, etc.
3. **Deadline Tracking**: Automatic calculation of due dates based on task type and RFP timelines
4. **Escalation**: Automatic notification to managers if task is overdue
5. **Dependencies**: Block task completion until dependencies are met
6. **Time Tracking**: Optional tracking of actual time spent vs. estimated
7. **Notes/Audit Trail**: Ability to add comments and track changes
8. **Attachment Support**: Attach relevant documents (resumes, offer letters, etc.)

## Implementation Approach

### Technology Options
1. **Leverage Existing Portal Task Framework**: Check if Crosshubber already has a task/notification system to extend
2. **Lightweight Internal Implementation**: Create simple notification/task entities if no existing framework
3. **Event-Driven Approach**: Use the event system (to be defined) to trigger notifications/tasks
4. **Integration with External Tools**: Consider integrating with existing task management systems (Asana, Jira, etc.) if appropriate

### Data Model Additions
If implementing internally:

#### Notification Entity
```
- id: UUID
- tenant_id: UUID
- rfp_id: UUID (optional, if RFP-specific)
- recipient_user_id: UUID
- template_id: UUID
- channel: ENUM (email, inapp, sms, slack)
- priority: ENUM (low, medium, high, urgent)
- status: ENUM (unread, read, archived)
- subject: String
- body: Text
- created_at: Timestamp
- read_at: Timestamp (nullable)
- related_entity_type: ENUM (rfp, candidate, shortlist, etc.)
- related_entity_id: UUID (nullable)
```

#### Task Entity
```
- id: UUID
- tenant_id: UUID
- rfp_id: UUID (optional)
- assignee_user_id: UUID
- reporter_user_id: UUID (who created the task)
- title: String
- description: Text
- task_type: ENUM (sourcing, screening, etc.)
- priority: ENUM (low, medium, high, urgent)
- status: ENUM (todo, in_progress, blocked, completed, cancelled)
- due_date: Timestamp
- estimated_effort: Integer (hours/minutes)
- actual_effort: Integer (nullable)
- dependencies: Array of UUID (task IDs that must complete first)
- created_at: Timestamp
- updated_at: Timestamp
- completed_at: Timestamp (nullable)
- related_entity_type: ENUM (rfp, candidate, etc.)
- related_entity_id: UUID (nullable)
```

### API Endpoints
If implementing internally:
- `GET /api/notifications` - List notifications for user
- `POST /api/notifications/{id}/read` - Mark as read
- `GET /api/tasks` - List tasks (with filtering options)
- `POST /api/tasks` - Create new task
- `PUT /api/tasks/{id}` - Update task
- `POST /api/tasks/{id}/complete` - Mark as complete
- `POST /api/tasks/{id}/block` - Mark as blocked
- `GET /api/task-types` - List available task types

### Integration Points
1. **Workflow Engine**: Notifications/tasks triggered by stage transitions
2. **User Assignment**: Automatic assignment based on role responsibilities
3. **SLA Monitoring**: Background process checking for SLA breaches
4. **Escalation Engine**: Automatic notifications for overdue items
5. **Email/SMS Gateway**: Integration with communication providers
6. **Calendar Integration**: For scheduling-related tasks (interviews, etc.)

## Implementation Recommendations

### Phase 1: Basic Notification System
1. Implement in-app notifications only (simplest to start)
2. Focus on critical workflow transitions (stage changes, approvals needed)
3. Use simple template system with basic variable substitution
4. Store notifications in database with simple retrieval

### Phase 2: Enhanced Notifications
1. Add email channel integration
2. Implement priority levels and escalation rules
3. Add scheduling capabilities for periodic summaries
4. Implement user preferences for notification channels/frequency

### Phase 3: Task Management System
1. Introduce task entities and management UI
2. Implement automatic task generation based on workflow stage
3. Add assignment rules and workload balancing
4. Implement task dependencies and blocking logic

### Phase 4: Advanced Features
1. Add SMS/Slack integrations for urgent notifications
2. Implement natural language processing for task creation from emails
3. Add predictive analytics for task duration estimation
4. Implement gamification/engagement features for task completion

## Benefits of Notification/Task System
1. **Reduced Manual Follow-up**: Less need for status check meetings/emails
2. **Improved Accountability**: Clear ownership of work items
3. **Faster Cycle Times**: Reduced delays due to missed communications
4. **Better Visibility**: Managers can see bottlenecks and workload distribution
5. **Enhanced User Experience**: Users get relevant information when needed
6. **Audit Trail**: Complete history of communications and actions
7. **Foundation for Analytics**: Data for process improvement initiatives

## Next Steps
1. Specify events to be consumed and published for integration
2. Expand admin settings to cover workflow, notifications, and integrations
3. Enhance agentic tools for workflow automation and external integrations
4. Identify required dependencies (CV/people DB, CRM, HRIS) and integration approaches