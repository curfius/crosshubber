# Events Specification for Staffing Module Integration

## Overview
This document defines the events that the staffing module should publish and consume to enable integration with external systems (CRM, HRIS, communication platforms, etc.) and support workflow automation. Events are a key mechanism for loose-coupling between systems and enabling real-time synchronization.

## Current State
The current staffing module has empty events arrays in its manifest:
```json
"events": { "published": [], "consumed": [] }
```

This means the module currently does not publish or consume any events, limiting its ability to integrate with other systems in the Crosshubber ecosystem or external enterprise systems.

## Event-Driven Architecture Principles
1. **Loose Coupling**: Systems communicate through events rather than direct calls
2. **Real-time Updates**: Changes propagate immediately when events are published
3. **Scalability**: Multiple consumers can react to the same event
4. **Resilience**: Temporary unavailability of consumers doesn't block the publisher
5. **Audit Trail**: Events provide a history of changes for debugging and compliance

## Events to Publish

The staffing module should publish events when significant changes occur in the RFP lifecycle or when data is updated that other systems might need to know about.

### RFP Lifecycle Events
These events correspond to major transitions in the enhanced workflow:

1. **rfp.drafted**
   - **Trigger**: When an RFP is created and saved in DRAFT state
   - **Payload**: 
     ```json
     {
       "rfpId": "uuid",
       "title": "string",
       "client": "string",
       "kind": "rfp|rfq",
       "createdBy": "userId",
       "createdAt": "timestamp",
       "initialRequirements": "object"
     }
     ```
   - **Purpose**: Notify systems that a new staffing need has been identified
   - **Potential Consumers**: CRM (to create opportunity), HRIS (for workforce planning), project management tools

2. **rfp.approved**
   - **Trigger**: When an RFP transitions from DRAFT/PENDING_APPROVAL to APPROVED state
   - **Payload**:
     ```json
     {
       "rfpId": "uuid",
       "title": "string",
       "client": "string",
       "kind": "rfp|rfq",
       "approvedBy": "userId",
       "approvedAt": "timestamp",
       "requirements": "object",
       "priority": "string",
       "expectedStartDate": "timestamp (nullable)",
       "duration": "string (nullable)"
     }
     ```
   - **Purpose**: Notify that approved staffing search can begin
   - **Potential Consumers**: Sourcing tools (to begin candidate search), HRIS (for headcount planning), finance systems (budget allocation)

3. **rfp.sourcing.started**
   - **Trigger**: When RFP enters SOURCING stage
   - **Payload**:
     ```json
     {
       "rfpId": "uuid",
       "sourcedBy": "userId",
       "startedAt": "timestamp",
       "sources": ["string"] // e.g., ["internal_db", "linkedin", "referrals"]
     }
     ```
   - **Purpose**: Notify that active candidate sourcing has begun
   - **Potential Consumers**: Recruitment marketing tools, sourcing analytics platforms

4. **rfp.screening.started**
   - **Trigger**: When RFP enters SCREENING stage
   - **Payload**:
     ```json
     {
       "rfpId": "uuid",
       "screenedBy": "userId",
       "startedAt": "timestamp",
       "candidateCount": "integer"
     }
     ```
   - **Purpose**: Notify that resume screening has begun
   - **Potential Consumers**: Interview scheduling systems, assessment tools

5. **rfp.matching.completed**
   - **Trigger**: When a matching run finishes and results are available
   - **Payload**:
     ```json
     {
       "rfpId": "uuid",
       "matchRunId": "uuid",
       "completedAt": "timestamp",
       "candidateCount": "integer",
       "topMatches": [
         {
           "candidateId": "uuid",
           "name": "string",
           "score": "number",
           "rationale": "string"
         }
       ],
       "matchingCriteria": "object" // requirements used
     }
     ```
   - **Purpose**: Notify that matching results are available for review
   - **Potential Consumers**: Candidate relationship management systems, reporting dashboards

6. **rfp.shortlisted**
   - **Trigger**: When candidates are selected and shortlist status is reached
   - **Payload**:
     ```json
     {
       "rfpId": "uuid",
       "shortlistId": "uuid",
       "createdBy": "userId",
       "createdAt": "timestamp",
       "candidateIds": ["uuid"],
       "position": "string",
       "location": "string (nullable)",
       "rateRange": "string (nullable)"
     }
     ```
   - **Purpose**: Notify that candidates have been selected for client submission
   - **Potential Consumers**: Client portal systems, CRM (to update opportunity stage), document generation systems

7. **rfp.submitted**
   - **Trigger**: When shortlist is submitted to client
   - **Payload**:
     ```json
     {
       "rfpId": "uuid",
       "submittedBy": "userId",
       "submittedAt": "timestamp",
       "submissionMethod": "string", // email, portal, etc.
       "clientContact": "string (nullable)",
       "expectedResponseDate": "timestamp (nullable)"
     }
     ```
   - **Purpose**: Notify that client has received the shortlist
   - **Potential Consumers**: CRM (to track submission), project management tools, client notification systems

8. **rfp.interviewing.started**
   - **Trigger**: When RFP enters INTERVIEWING stage
   - **Payload**:
     ```json
     {
       "rfpId": "uuid",
       "startedAt": "timestamp",
       "interviewType": "string", // phone, video, in-person, technical
       "scheduledInterviews": [
         {
           "candidateId": "uuid",
           "scheduledAt": "timestamp",
           "interviewers": ["userId"]
         }
       ]
     }
     ```
   - **Purpose**: Notify that interview process has begun
   - **Potential Consumers**: Calendar systems (to block time), video conferencing tools, interview scheduling platforms

9. **rfp.offer.extended**
   - **Trigger**: When formal job offer is extended to candidate
   - **Payload**:
     ```json
     {
       "rfpId": "uuid",
       "offerId": "uuid",
       "extendedTo": "uuid", // candidateId
       "extendedBy": "userId",
       "extendedAt": "timestamp",
       "position": "string",
       "compensation": "object", // salary, bonus, equity, etc.
       "startDate": "timestamp",
       "responseDeadline": "timestamp"
     }
     ```
   - **Purpose**: Notify that offer has been made
   - **Potential Consumers**: HRIS (pre-onboarding), background check systems, document generation (offer letters)

10. **rfp.offer.accepted**
    - **Trigger**: When candidate accepts the job offer
    - **Payload**:
      ```json
      {
        "rfpId": "uuid",
        "offerId": "uuid",
        "candidateId": "uuid",
        "acceptedAt": "timestamp",
        "startDate": "timestamp",
        "acceptanceTerms": "object" // any negotiated changes
      }
      ```
    - **Purpose**: Notify that candidate has accepted the offer
    - **Potential Consumers**: HRIS (to create employee record), payroll systems, benefits administration, onboarding systems

11. **rfp.onboarded**
    - **Trigger**: When candidate completes onboarding and starts work
    - **Payload**:
      ```json
      {
        "rfpId": "uuid",
        "candidateId": "uuid",
        "startDate": "timestamp",
        "completedAt": "timestamp",
        "onboardingCoordinator": "userId",
        "documentsCompleted": ["string"] // I-9, W-4, etc.
      }
      ```
    - **Purpose**: Notify that candidate is now active employee
    - **Potential Consumers**: HRIS (activate employee), payroll systems, access provisioning, project assignment systems

12. **rfp.completed**
    - **Trigger**: When placement is successfully completed (end of guarantee period)
    - **Payload**:
      ```json
      {
        "rfpId": "uuid",
        "completedAt": "timestamp",
        "success": "boolean",
        "finalPerformanceRating": "string (nullable)",
        "notes": "string (nullable)"
      }
      ```
    - **Purpose**: Notify that placement has ended successfully
    - **Potential Consumers**: HRIS (update employment status), finance systems (final billing), CRM (update client satisfaction), analytics systems

13. **rfp.closed**
    - **Trigger**: When position is filled through other means or requirement cancelled
    - **Payload**:
      ```json
      {
        "rfpId": "uuid",
        "closedAt": "timestamp",
        "reason": "string", // filled_elsewhere, budget_cancelled, requirement_changed, etc.
        "closedBy": "userId",
        "alternativeSolution": "string (nullable)" // if filled through other means
      }
      ```
    - **Purpose**: Notify that RFP is no longer active
    - **Potential Consumers**: CRM (close opportunity), HRIS (remove from planning), finance systems (adjust budget)

14. **rfp.archived**
    - **Trigger**: When RFP is moved to historical archive
    - **Payload**:
      ```json
      {
        "rfpId": "uuid",
        "archivedAt": "timestamp",
        "archivedBy": "userId",
        "archiveReason": "string" // completed, closed, obsolete, etc.
      }
      ```
    - **Purpose**: Notify for data retention and compliance purposes
    - **Potential Consumers**: Data warehousing systems, compliance reporting, long-term storage

### Candidate-Related Events

15. **candidate.added**
    - **Trigger**: When new candidate profile is created in the system
    - **Payload**:
      ```json
      {
        "candidateId": "uuid",
        "sourceRef": "string", // external reference (LinkedIn, referral code, etc.)
        "name": "string",
        "headline": "string (nullable)",
        "skills": ["string"],
        "seniority": "string (nullable)",
        "languages": ["string"],
        "availability": "string (nullable)",
        "addedBy": "userId",
        "addedAt": "timestamp",
        "source": "string" // internal_upload, api_import, etc.
      }
      ```
    - **Purpose**: Notify of new talent available for matching
    - **Potential Consumers**: Talent CRM systems, sourcing tools, diversity tracking systems

16. **candidate.updated**
    - **Trigger**: When existing candidate profile is significantly updated
    - **Payload**:
      ```json
      {
        "candidateId": "uuid",
        "updatedFields": ["string"], // which fields changed
        "updatedBy": "userId",
        "updatedAt": "timestamp",
        "previousValues": "object (nullable)", // for audit
        "currentValues": "object"
      }
      ```
    - **Purpose**: Notify of changes to candidate information
    - **Potential Consumers**: Talent CRM systems, candidate relationship management, skills inventory systems

17. **candidate.contacted**
    - **Trigger**: When system records contact with a candidate for a specific RFP
    - **Payload**:
      ```json
      {
        "candidateId": "uuid",
        "rfpId": "uuid",
        "contactType": "string", // email, phone, inperson, etc.
        "contactedAt": "timestamp",
        "contactedBy": "userId",
        "outcome": "string" // interested, not_interested, no_response, etc.
      }
      ```
    - **Purpose**: Track candidate engagement history
    - **Potential Consumers**: Candidate relationship management systems, recruitment marketing platforms

### Matching and Process Events

18. **match.run.completed**
    - **Trigger**: When any matching algorithm run finishes (not just RFP-specific)
    - **Payload**:
      ```json
      {
        "matchRunId": "uuid",
        "rfpId": "uuid (nullable)", // null for ad-hoc matches
        "completedAt": "timestamp",
        "criteria": "object",
        "candidateCount": "integer",
        "results": [
          {
            "candidateId": "uuid",
            "score": "number",
            "matchDetails": "object"
          }
        ],
        "processingTimeMs": "integer"
      }
      ```
    - **Purpose**: Notify of matching activity for performance monitoring
    - **Potential Consumers**: Performance monitoring systems, algorithm optimization tools, audit systems

19. **shortlist.created**
    - **Trigger**: When a shortlist is created (independent of RFP state)
    - **Payload**:
      ```json
      {
        "shortlistId": "uuid",
        "rfpId": "uuid",
        "createdBy": "userId",
        "createdAt": "timestamp",
        "candidateIds": ["uuid"],
        "purpose": "string" // client_submission, internal_review, etc.
      }
      ```
    - **Purpose**: Notify of shortlist creation for tracking
    - **Potential Consumers**: Reporting systems, candidate management tools, quality assurance systems

### System and Operational Events

20. **rfp.sla.warning**
    - **Trigger**: When RFP time in stage approaches SLA threshold
    - **Payload**:
      ```json
      {
        "rfpId": "uuid",
        "currentStage": "string",
        "timeInStage": "integer (hours)",
        "slaThreshold": "integer (hours)",
        "timeRemaining": "integer (hours)",
        "escalationLevel": "integer" // 1=warning, 2=overdue, 3=escalated
      }
      ```
    - **Purpose**: Enable proactive SLA management
    - **Potential Consumers**: Manager dashboards, escalation systems, performance analytics

21. **workflow.task.assigned**
    - **Trigger**: When a task is assigned to a user in the task management system
    - **Payload**:
      ```json
      {
        "taskId": "uuid",
        "rfpId": "uuid (nullable)",
        "taskType": "string",
        "assignedTo": "userId",
        "assignedBy": "userId (nullable)", // null if auto-assigned
        "assignedAt": "timestamp",
        "dueDate": "timestamp",
        "priority": "string"
      }
      ```
    - **Purpose**: Synchronize task assignments with external task management systems
    - **Potential Consumers**: External task tools (Asana, Jira, Trello), personal productivity apps

22. **workflow.task.completed**
    - **Trigger**: When a task is marked as completed
    - **Payload**:
      ```json
      {
        "taskId": "uuid",
        "rfpId": "uuid (nullable)",
        "completedBy": "userId",
        "completedAt": "timestamp",
        "actualEffort": "integer (nullable)",
        "outcome": "string" // success, partially_completed, blocked, etc.
      }
      ```
    - **Purpose**: Track task completion for productivity metrics
    - **Potential Consumers**: Time tracking systems, performance review tools, billing systems (if applicable)

## Events to Consume

The staffing module should consume events from external systems to keep its data synchronized and trigger workflow actions based on external changes.

### CRM Integration Events

23. **crm.opportunity.created**
    - **Trigger**: When a new opportunity is created in CRM system
    - **Payload** (expected):
      ```json
      {
        "opportunityId": "string",
        "accountId": "string",
        "accountName": "string",
        "opportunityName": "string",
        "stage": "string",
        "amount": "number (nullable)",
        "closeDate": "timestamp (nullable)",
        "productInterest": ["string"],
        "createdBy": "string",
        "createdAt": "timestamp"
      }
      ```
    - **Action**: Optionally create an RFP in DRAFT state mapped to this opportunity
    - **Mapping Logic**: 
      - opportunityName → RFP title
      - accountName → RFP client
      - productInterest → initial requirements/skills
      - amount → budget information (if available)
      - closeDate → target deadline

24. **crm.opportunity.updated**
    - **Trigger**: When opportunity stage or key fields change
    - **Payload** (expected):
      ```json
      {
        "opportunityId": "string",
        "changes": {
          "stage": {"old": "string", "new": "string"},
          "amount": {"old": "number", "new": "number"},
          "closeDate": {"old": "timestamp", "new": "timestamp"}
        },
        "updatedAt": "timestamp"
      }
      ```
    - **Action**: Update related RFP status or fields based on opportunity changes
    - **Mapping Examples**:
      - Stage change to "Closed Won" → RFP → CLOSED (success)
      - Stage change to "Closed Lost" → RFP → CLOSED (unfilled)
      - Close date changed → Update RFP deadline

25. **crm.activity.logged**
    - **Trigger**: When significant activity is logged against opportunity
    - **Payload** (expected):
      ```json
      {
        "activityId": "string",
        "opportunityId": "string",
        "activityType": "string", // Call, Email, Meeting, Task
        "subject": "string",
        "description": "string",
        "loggedBy": "string",
        "loggedAt": "timestamp"
      }
      ```
    - **Action**: Create internal note or task on related RFP
    - **Examples**:
      - ActivityType = Meeting with title "Interview Feedback" → Create interview feedback task
      - ActivityType = Email with subject containing "Offer" → Track offer communication

### HRIS Integration Events

26. **hrms.employee.hired**
    - **Trigger**: When new employee record is activated in HRIS
    - **Payload** (expected):
      ```json
      {
        "employeeId": "string",
        "personId": "string",
        "hireDate": "timestamp",
        "position": "string",
        "department": "string",
        "managerId": "string",
        "location": "string",
        "employmentType": "string", // full_time, part_time, contractor
        "createdAt": "timestamp"
      }
      ```
    - **Action**: Validate against onboarded candidates, update placement status
    - **Matching Logic**: 
      - Match by employeeId if previously shared
      - Match by name + startDate + position if employeeId not available
      - Update RFP to COMPLETED if match found and dates align

27. **hrms.employee.terminated**
    - **Trigger**: When employee record is terminated in HRIS
    - **Payload** (expected):
      ```json
      {
        "employeeId": "string",
        "terminationDate": "timestamp",
        "terminationType": "string", // voluntary, involuntary, end_contract
        "lastDayWorked": "timestamp",
        "updatedAt": "timestamp"
      }
      ```
    - **Action**: Update placement tracking, potentially reopen RFP if premature termination

28. **hrms.contractor.completed**
    - **Trigger**: When contractor engagement ends in HRIS
    - **Payload** (expected):
      ```json
      {
        "contractorId": "string",
        "endDate": "timestamp",
        "reason": "string",
        "updatedAt": "timestamp"
      }
      ```
    - **Action**: Update related RFP to COMPLETED if endDate matches expected completion

### Communication and Collaboration Events

29. **email.response.received**
    - **Trigger**: When email response is received from candidate or client
    - **Payload** (expected):
      ```json
      {
        "messageId": "string",
        "threadId": "string",
        "from": "string",
        "to": ["string"],
        "subject": "string",
        "receivedAt": "timestamp",
        "bodyPreview": "string (first 100 chars)",
        "hasAttachments": "boolean"
      }
      ```
    - **Action**: 
      - Parse for interview scheduling requests
      - Detect offer acceptance/rejection language
      - Extract feedback from client interviews
      - Create tasks for follow-up as needed

30. **calendar.event.created**
    - **Trigger**: When calendar event is created that matches RFP patterns
    - **Payload** (expected):
      ```json
      {
        "eventId": "string",
        "calendarId": "string",
        "title": "string",
        "description": "string (nullable)",
        "startAt": "timestamp",
        "endAt": "timestamp",
        "organizer": "string",
        "attendees": ["string"],
        "location": "string (nullable)",
        "meetingUrl": "string (nullable)"
      }
      ```
    - **Action**:
      - If title contains RFP ID or candidate name → link to RFP/candidate
      - If attendees include external parties → potentially client interview
      - Create interview scheduling tasks or update existing ones

31. **teams.slack.message.received**
    - **Trigger**: When message received in monitored channels
    - **Payload** (expected):
      ```json
      {
        "messageId": "string",
        "channelId": "string",
        "senderId": "string",
        "message": "string",
        "sentAt": "timestamp",
        "threadTs": "string (nullable)"
      }
      ```
    - **Action**:
      - Monitor for recruitment-related keywords
      - Detect interview coordination messages
      - Extract candidate feedback from team discussions
      - Create notifications/tasks as appropriate

## Event Metadata Standards

All events should include standard metadata for tracing and processing:

```json
{
  "eventId": "uuid", // unique identifier for this event occurrence
  "eventType": "string", // e.g., "rfp.approved"
  "eventVersion": "string", // schema version for evolution
  "timestamp": "timestamp", // when event occurred
  "tenantId": "uuid", // multi-tenant isolation
  "triggeredBy": "string", // userId or system name that caused the event
  "correlationId": "uuid (optional)", // for tracing related events across systems
  "source": "string", // "staffing-module" or other system identifier
  "payload": "object" // the actual event data as defined above
}
```

## Implementation Considerations

### Publishing Events
1. **Where to Publish**: 
   - In service methods after successful state transitions
   - In repository save methods for entity changes
   - In controller methods for user-initiated actions
   - In scheduled jobs for time-based events (SLA warnings)

2. **Reliability**:
   - Use transactional outbox pattern to ensure events are published after DB commit
   - Implement retry mechanisms with exponential backoff
   - Consider dead letter queues for repeatedly failing events

3. **Performance**:
   - Publish events asynchronously where possible
   - Batch similar events if appropriate
   - Consider event filtering to reduce noise

### Consuming Events
1. **Where to Consume**:
   - In dedicated event handler services
   - In controller methods for immediate user feedback
   - In scheduled jobs for batch processing
   - In workflow engines for automatic transitions

2. **Idempotency**:
   - Design consumers to handle duplicate events safely
   - Track processed eventIds to prevent double-processing
   - Use conditions to avoid unnecessary updates

3. **Error Handling**:
   - Log failed event processing for manual review
   - Implement circuit breaker patterns for downstream system issues
   - Provide manual replay capabilities for failed events

### Schema Evolution
1. **Versioning**: Include version in event metadata to handle schema changes
2. **Backward Compatibility**: Make changes backward compatible where possible
3. **Deprecation**: Mark fields as deprecated before removing them
4. **Validation**: Validate incoming events against expected schemas

## Implementation Recommendations

### Phase 1: Core Lifecycle Events
1. Start with publishing key RFP lifecycle events:
   - rfp.drafted, rfp.approved, rfp.submitted, rfp.offer.accepted, rfp.completed, rfp.closed
2. Implement basic publishing mechanism using Spring ApplicationEvents or similar
3. Add simple JSON payload structure

### Phase 2: Enhanced Lifecycle and Candidate Events
1. Add more granular stage transition events
2. Implement candidate-related events
3. Add proper metadata and correlation IDs
4. Implement reliability mechanisms (retry, dead letter queue)

### Phase 3: Consumed Events and Integration
1. Begin consuming key external events:
   - crm.opportunity.created/updated
   - hrms.employee.hired
   - email.response.received
2. Implement event handlers that trigger appropriate workflow actions
3. Add mapping and transformation logic

### Phase 4: Operational and System Events
1. Add SLA warning events
2. Implement task-related events if task management is added
3. Add system health and performance events
4. Implement comprehensive monitoring and alerting based on events

## Benefits of Event-Driven Integration
1. **Real-time Synchronization**: External systems stay updated with staffing module changes
2. **Reduced Coupling**: Staffing module doesn't need to know about specific consumers
3. **Improved Reliability**: Event queuing provides buffering during system outages
4. **Enhanced Analytics**: Events provide rich data for process mining and optimization
5. **Workflow Automation**: External events can trigger internal workflow actions
6. **Compliance and Auditing**: Complete history of changes for regulatory requirements
7. **Ecosystem Enablement**: Enables richer integrations within Crosshubber portal and with external systems

## Next Steps
1. Expand admin settings to cover workflow, notifications, and integrations
2. Enhance agentic tools for workflow automation and external integrations
3. Identify required dependencies (CV/people DB, CRM, HRIS) and integration approaches