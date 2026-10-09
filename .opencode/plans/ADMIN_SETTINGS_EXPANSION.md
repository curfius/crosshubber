# Admin Settings Expansion for Staffing Module

## Overview
This document proposes an expanded set of admin settings for the staffing module to support the enhanced workflow, notification/task systems, event-driven integrations, and overall operational needs. The current settings are limited to tool toggles and matching parameters, which is insufficient for a comprehensive staffing RFP/RFQ management system.

## Current Admin Settings Limitations
The existing settings only cover:
1. **Tool Toggles**: Enable/disable specific agent tools (list_rfps, get_rfp, search_cvs, match_candidates, create_match_run)
2. **Matching Parameters**: Default shortlist size (topN) with range 1-50

This leaves critical aspects of the staffing process unconfigurable, requiring code changes for adjustments that should be administrable.

## Proposed Admin Settings Structure

The admin settings should be organized into logical sections that correspond to different aspects of the staffing module functionality:

### 1. Workflow Configuration Settings
Settings that control the behavior of the enhanced RFP/RFQ workflow.

#### Workflow Definition
- **enable_enhanced_workflow**: Boolean - Switch between basic and enhanced workflow
- **workflow_version**: String - Version of the workflow definition being used
- **allow_stage_skipping**: Boolean - Whether users can skip stages (not recommended for compliance)
- **require_stage_completion**: Boolean - Whether all required tasks in a stage must be completed before progressing

#### Stage-Specific SLAs (Service Level Agreements)
For each stage, define expected completion times:
- **draft_stage_sla_hours**: Integer (default: 16) - Time to finalize requirements
- **approval_stage_sla_hours**: Integer (default: 8) - Time for approval decision
- **sourcing_stage_sla_hours**: Integer (default: 72) - Time to source initial candidates
- **screening_stage_sla_hours**: Integer (default: 48) - Time to screen candidates
- **matching_stage_sla_hours**: Integer (default: 24) - Time to run and review matching
- **shortlisting_stage_sla_hours**: Integer (default: 24) - Time to create shortlist
- **submission_stage_sla_hours**: Integer (default: 4) - Time to submit to client after approval
- **interviewing_stage_sla_hours**: Integer (default: 120) - Time for client to complete interviews
- **offer_stage_sla_hours**: Integer (default: 48) - Time for candidate to respond to offer
- **onboarding_stage_sla_hours**: Integer (default: 168) - Time to complete onboarding (1 week)

#### Stage Automation Rules
- **auto_transition_on_sla_exceed**: Boolean - Whether to auto-escalate or move stages when SLA exceeded
- **auto_create_matching_run**: Boolean - Whether to automatically run matching when enough candidates are sourced
- **auto_shortlist_threshold**: Integer (default: 80) - Minimum match score to auto-suggest for shortlist
- **auto_notify_on_stage_change**: Boolean - Whether to automatically send notifications when stage changes

### 2. Notification and Communication Settings
Settings that control how notifications are sent and what templates are used.

#### Notification Channels
- **email_enabled**: Boolean - Whether to send email notifications
- **inapp_enabled**: Boolean - Whether to show in-app notifications (always true if module loaded)
- **sms_enabled**: Boolean - Whether to send SMS notifications
- **slack_enabled**: Boolean - Whether to send Slack notifications
- **teams_enabled**: Boolean - Whether to send Microsoft Teams notifications

#### Email Configuration
- **email_from_address**: String - From address for outgoing emails
- **email_reply_to**: String - Reply-to address
- **email_smtp_host**: String - SMTP server hostname
- **email_smtp_port**: Integer - SMTP server port
- **email_smtp_username**: String - SMTP username (if authentication required)
- **email_smtp_password**: String - SMTP password (encrypted)
- **email_use_tls**: Boolean - Whether to use TLS encryption
- **email_batch_size**: Integer - Number of emails to send in one batch
- **email_rate_limit_per_hour**: Integer - Maximum emails to send per hour

#### SMS Configuration
- **sms_provider**: String - Provider name (twilio, nexmo, etc.)
- **sms_account_sid**: String - Account identifier
- **sms_auth_token**: String - Authentication token (encrypted)
- **sms_from_number**: String - Phone number to send from

#### Slack/Teams Configuration
- **slack_webhook_url**: String - Webhook URL for Slack notifications
- **slack_channel**: String - Default channel for notifications
- **teams_webhook_url**: String - Webhook URL for Teams notifications

#### Notification Templates
Template configuration for different notification types (would typically reference template IDs or content):
- **notification_template_rfp_created**: String - Template ID/content
- **notification_template_approval_required**: String - Template ID/content
- **notification_template_stage_changed**: String - Template ID/content
- **notification_template_action_required**: String - Template ID/content
- **notification_template_sla_warning**: String - Template ID/content
- **notification_template_milestone_reached**: String - Template ID/content
- **notification_template_blocked_issue**: String - Template ID/content
- **notification_template_periodic_summary**: String - Template ID/content

#### Notification Preferences
- **notification_frequency**: ENUM (immediate, hourly_digest, daily_digest) - How frequently to send non-urgent notifications
- **quiet_hours_start**: String (HH:MM) - Start of quiet hours (no notifications)
- **quiet_hours_end**: String (HH:MM) - End of quiet hours
- **weekend_notifications**: Boolean - Whether to send notifications on weekends
- **urgent_notification_override_quiet_hours**: Boolean - Whether urgent notifications bypass quiet hours

### 3. Integration Settings
Settings that control how the staffing module connects to external systems.

#### CRM Integration
- **crm_integration_enabled**: Boolean - Whether CRM integration is active
- **crm_type**: ENUM (salesforce, hubspot, dynamics, custom) - Type of CRM system
- **crm_api_url**: String - Base URL for CRM API
- **crm_api_version**: String - API version to use
- **crm_client_id**: String - OAuth client ID
- **crm_client_secret**: String - OAuth client secret (encrypted)
- **crm_username**: String - Username for authentication (if applicable)
- **crm_password**: String - Password for authentication (encrypted)
- **crm_opportunity_stage_mapping**: JSON - Mapping between opportunity stages and RFP stages
  ```json
  {
    "Prospecting": "DRAFT",
    "Qualification": "APPROVED",
    "Proposal/Price Quote": "SOURCING",
    "Negotiation/Review": "SHORTLISTED",
    "Closed Won": "COMPLETED",
    "Closed Lost": "CLOSED"
  }
  ```
- **crm_sync_direction**: ENUM (bidirectional, to_crm, from_crm) - Direction of synchronization
- **crm_sync_frequency_minutes**: Integer - How often to sync (if not real-time via events)
- **crm_create_opportunity_from_rfp**: Boolean - Whether to automatically create CRM opportunity when RFP is approved

#### HRIS Integration
- **hris_integration_enabled**: Boolean - Whether HRIS integration is active
- **hris_type**: ENUM (workday, sap_successfactors, oracle_hcm, custom) - Type of HRIS system
- **hris_api_url**: String - Base URL for HRIS API
- **hris_api_version**: String - API version to use
- **hris_client_id**: String - OAuth client ID
- **hris_client_secret**: String - OAuth client secret (encrypted)
- **hris_username**: String - Username for authentication (if applicable)
- **hris_password**: String - Password for authentication (encrypted)
- **hris_create_employee_from_onboarded**: Boolean - Whether to automatically create employee record when candidate is onboarded
- **hris_termination_triggers_rfp_reopen**: Boolean - Whether premature termination should reopen RFP
- **hris_sync_fields**: Array<String> - Which fields to synchronize (employeeId, hireDate, position, etc.)

#### Communication Integration
- **email_monitoring_enabled**: Boolean - Whether to monitor incoming emails for RFP-related communications
- **email_monitoring_address**: String - Email address to monitor
- **email_monitoring_protocol**: ENUM (imap, pop3) - Protocol to use
- **email_monitoring_host**: String - Email server host
- **email_monitoring_port**: Integer - Email server port
- **email_monitoring_username**: String - Username for email monitoring
- **email_monitoring_password**: String - Password for email monitoring (encrypted)
- **email_monitoring_use_tls**: Boolean - Whether to use TLS for email monitoring
- **email_monitoring_check_frequency_minutes**: Integer - How often to check for new emails

- **calendar_integration_enabled**: Boolean - Whether to integrate with calendar systems
- **calendar_type**: ENUM (google, outlook, exchange) - Type of calendar system
- **calendar_api_credentials**: String - Credentials for calendar API (encrypted)
- **calendar_monitor_calendars**: Array<String> - Which calendars to monitor for RFP-related events
- **calendar_create_event_for_interviews**: Boolean - Whether to automatically create calendar events for interviews

#### Document Storage Integration
- **document_storage_enabled**: Boolean - Whether to use external document storage
- **storage_type**: ENUM (sharepoint, google_drive, aws_s3, azure_blob) - Type of storage
- **storage_credentials**: String - Credentials for storage API (encrypted)
- **storage_base_path**: String - Base path for storing staffing module documents
- **storage_rfp_folder_pattern**: String - Pattern for RFP document folders (e.g., "/staffing/rfps/{rfpId}")
- **storage_candidate_folder_pattern**: String - Pattern for candidate document folders
- **storage_enable_versioning**: Boolean - Whether to enable document versioning
- **storage_retention_days**: Integer - How long to retain documents (for compliance)

#### Event Streaming/Message Queue
- **event_publishing_enabled**: Boolean - Whether to publish events to message queue
- **event_streaming_type**: ENUM (kafka, rabbitmq, aws_sns, azure_service_bus, custom) - Type of event streaming
- **event_streaming_servers**: String - Connection string/servers for event streaming
- **event_streaming_topic_prefix**: String - Prefix for event topics (e.g., "staffing.")
- **event_streaming_username**: String - Username for authentication (if required)
- **event_streaming_password**: String - Password for authentication (encrypted)
- **event_publish_reliability**: ENUM (at_least_once, at_most_once, exactly_once) - Delivery guarantee
- **event_publish_batch_size**: Integer - Number of events to batch before publishing
- **event_publish_linger_ms**: Integer - Time to wait for additional events before publishing batch

### 4. Matching and Search Settings
Enhanced settings for the candidate matching functionality beyond the basic topN parameter.

#### Matching Algorithm Weights
Configurable weights for different matching factors (should sum to 100 or be normalized):
- **matching_weight_skills**: Integer (default: 40) - Weight for skills matching
- **matching_weight_seniority**: Integer (default: 20) - Weight for seniority match
- **matching_weight_languages**: Integer (default: 15) - Weight for language match
- **matching_weight_availability**: Integer (default: 15) - Weight for availability match
- **matching_weight_experience_years**: Integer (default: 10) - Weight for years of experience match
- **matching_weight_education**: Integer (default: 0) - Weight for education match (if implemented)
- **matching_weight_certifications**: Integer (default: 0) - Weight for certification match (if implemented)

#### Matching Thresholds
- **matching_minimum_score_threshold**: Integer (default: 30) - Minimum score to consider a candidate a match
- **matching_skills_exact_match_bonus**: Integer (default: 10) - Bonus points for exact skill match
- **matching_seniority_exact_match_bonus**: Integer (default: 5) - Bonus points for exact seniority match
- **matching_language_bonus_per_language**: Integer (default: 5) - Bonus points per matching language
- **matching_availability_exact_match_bonus**: Integer (default: 10) - Bonus points for exact availability match

#### Search and Sourcing Settings
- **sourcing_max_results_per_query**: Integer (default: 100) - Maximum results to fetch per sourcing query
- **sourcing_enable_boolean_search**: Boolean - Whether to support boolean operators in sourcing
- **sourcing_default_locations**: Array<String> - Default locations to search if not specified in RFP
- **sourcing_remote_work_keywords**: Array<String> - Keywords that indicate remote work compatibility
- **sourcing_relocation_indicators**: Array<String> - Keywords that indicate willingness to relocate

#### CV Parsing and Extraction Settings
- **cv_extraction_method**: ENUM (naive_keyword, llm_assisted, hybrid) - Method for extracting CV information
- **llm_model_for_extraction**: String - Name/ID of LLM to use for extraction (if LLM-assisted)
- **cv_skills_dictionary**: Array<String> - Custom skills dictionary for extraction (overrides default)
- **cv_ignore_common_words**: Boolean - Whether to ignore common words like "and", "the", etc. in skills extraction
- **cv_minimum_skill_confidence**: Float (0.0-1.0) - Minimum confidence score to consider a skill extracted
- **cv_experience_extraction_enabled**: Boolean - Whether to attempt to extract years of experience
- **cv_education_extraction_enabled**: Boolean - Whether to attempt to extract education information

### 5. Security and Compliance Settings
Settings related to data protection, audit trails, and regulatory compliance.

#### Data Retention
- **rfp_data_retention_days**: Integer (default: 2555) - How long to retain RFP data (7 years for compliance)
- **candidate_data_retention_days**: Integer (default: 1825) - How long to retain candidate data (5 years)
- **communication_data_retention_days**: Integer (default: 1095) - How long to retain emails/notes (3 years)
- **analytics_data_retention_days**: Integer (default: 365) - How long to retain analytics/aggregated data
- **enable_auto_archival**: Boolean - Whether to automatically archive old data based on retention policies
- **archival_notification_days_before**: Integer (default: 30) - Days before archival to send notification

#### Audit and Logging
- **enable_detailed_audit_logging**: Boolean - Whether to log detailed user actions
- **audit_log_include_payloads**: Boolean - Whether to include request/response payloads in audit logs (caution with PII)
- **audit_log_retention_days**: Integer (default: 365) - How long to retain audit logs
- **log_sensitive_data_handling**: ENUM (mask, hash, omit, full) - How to handle sensitive data in logs
- **enable_phi_detection**: Boolean - Whether to attempt to detect and protect PHI (Personal Health Information)
- **enable_pii_detection**: Boolean - Whether to attempt to detect and protect PII (Personally Identifiable Information)

#### Access Controls
- **session_timeout_minutes**: Integer (default: 480) - How long until inactive sessions timeout (8 hours)
- **require_mfa_for_admin**: Boolean - Whether to require multi-factor authentication for admin actions
- **allow_concurrent_sessions**: Boolean - Whether users can have multiple simultaneous sessions
- **max_failed_login_attempts**: Integer (default: 5) - Number of failed logins before account lockout
- **account_lockout_duration_minutes**: Integer (default: 30) - How long accounts remain locked after max failed attempts

#### Data Privacy and Consent
- **gdpr_compliance_mode**: Boolean - Whether to enable GDPR-specific features
- **data_subject_request_automation**: Boolean - Whether to automate responses to data subject requests
- **consent_required_for_cv_processing**: Boolean - Whether explicit consent is required to process CVs
- **consent_required_for_communication_tracking**: Boolean - Whether consent is required to track email/phone communications
- **data_processing_purpose_field**: String - Field to capture purpose of data processing (for compliance)

### 6. Reporting and Analytics Settings
Settings that control what metrics are collected and how reporting works.

#### Metrics Collection
- **enable_performance_metrics**: Boolean - Whether to collect detailed performance metrics
- **metrics_collection_level**: ENUM (basic, standard, comprehensive) - Depth of metrics collection
- **track_time_in_stage**: Boolean - Whether to track how long RFPs spend in each stage
- **track_conversion_rates**: Boolean - Whether to track conversion rates between stages
- **track_recruiter_metrics**: Boolean - Whether to track individual recruiter performance
- **track_source_effectiveness**: Boolean - Whether to track effectiveness of different sourcing channels
- **track_matching_accuracy**: Boolean - Whether to track how well matching predicts successful placements
- **track_client_satisfaction**: Boolean - Whether to track client satisfaction scores (if collected)

#### Reporting Configuration
- **reporting_currency**: String - Default currency for financial reporting (USD, EUR, etc.)
- **reporting_date_format**: String - Default date format for reports
- **reporting_timezone**: String - Default timezone for reporting
- **enable_real_time_dashboards**: Boolean - Whether to support real-time updating dashboards
- **dashboard_refresh_interval_seconds**: Integer - How often to refresh real-time dashboards
- **report_generation_timeout_seconds**: Integer - Maximum time allowed for report generation
- **max_report_results_rows**: Integer - Maximum number of rows to return in reports
- **enable_report_scheduling**: Boolean - Whether to allow scheduling of recurring reports
- **max_scheduled_reports_per_user**: Integer - Maximum number of scheduled reports per user

#### Analytics Integration
- **analytics_export_enabled**: Boolean - Whether to allow exporting data to external analytics tools
- **analytics_export_formats**: Array<ENUM> - Supported export formats (csv, excel, json, pdf)
- **analytics_api_enabled**: Boolean - Whether to expose analytics via API
- **analytics_api_rate_limit_per_hour**: Integer - API rate limit for analytics endpoints
- **enable_predictive_analytics**: Boolean - Whether to enable predictive features (e.g., time-to-fill prediction)
- **predictive_model_retraining_frequency_days**: Integer - How often to retrain predictive models

### 7. User Interface and Experience Settings
Settings that affect how users interact with the module.

#### Localization and Format
- **default_language**: String - Default language for UI (en, es, fr, etc.)
- **supported_languages**: Array<String> - Languages available for users to choose from
- **date_format**: String - Preferred date format (MM/DD/YYYY, DD/MM/YYYY, YYYY-MM-DD)
- **time_format**: String - Preferred time format (12h or 24h)
- **first_day_of_week**: ENUM (sunday, monday) - Which day is considered first day of week
- **number_format**: String - How to format numbers (for different locales)
- **currency_display**: ENUM (symbol, code, none) - How to display currency

#### UI Customization
- **module_accent_color**: String - Hex color for module-specific accent elements
- **module_logo_url**: String - URL to custom logo for the module
- **enable_custom_css**: Boolean - Whether to allow custom CSS overrides
- **custom_css_url**: String - URL to custom CSS file (if enabled)
- **items_per_page_default**: Integer - Default number of items to show in lists/tables
- **items_per_page_options**: Array<Integer> - Available options for items per page (e.g., [10, 25, 50, 100])
- **enable_advanced_search**: Boolean - Whether to show advanced search options by default
- **save_searches_enabled**: Boolean - Whether users can save frequently used searches

#### Help and Support
- **enable_in_app_help**: Boolean - Whether to show contextual help tips
- **help_content_url**: String - URL to external help/documentation
- **support_contact_email**: String - Email address for support requests
- **support_contact_phone**: String - Phone number for support
- **enable_feedback_mechanism**: Boolean - Whether to allow users to submit feedback
- **feedback_destination**: ENUM (email, ticket_system, internal) - Where feedback should go

### 8. Feature Flags and Experimental Settings
Settings for enabling/disabling features and testing new functionality.

#### Core Feature Toggles
- **enable_workflow_automation**: Boolean - Whether to enable automatic workflow transitions based on rules
- **enable_predictive_matching**: Boolean - Whether to use ML-enhanced matching algorithms
- **enable_candidate_rediscovery**: Boolean - Whether to automatically re-engage past candidates for new RFPs
- **enable_client_portal**: Boolean - Whether to provide clients with limited portal access to view status
- **enable_vendor_management**: Boolean - Whether to manage third-party staffing vendors through the module
- **enable_diversity_tracking**: Boolean - Whether to track diversity metrics in sourcing and placement
- **enable_skills_inference**: Boolean - Whether to infer skills from job titles/experience when not explicitly stated
- **enable_location_flexibility_matching**: Boolean - Whether to consider remote work and relocation willingness in matching

#### Experimental Features
- **experimental_llm_rfp_parsing**: Boolean - Whether to use LLM to parse RFP documents for requirements
- **experimental_video_interviewing**: Boolean - Whether to integrate video interview platforms
- **experimental_skills_assessment**: Boolean - Whether to integrate skills testing platforms
- **experimental_blockchain_credential_verification**: Boolean - Whether to verify credentials via blockchain
- **experimental_ai_sourcing_assistant**: Boolean - Whether to use AI to suggest sourcing strategies
- **experimental_gamification**: Boolean - Whether to add gamification elements to encourage user engagement

#### Release and Deployment
- **maintenance_mode**: Boolean - Whether the module is in maintenance mode (read-only)
- **maintenance_message**: String - Message to show users when in maintenance mode
- **enable_beta_features**: Boolean - Whether to show beta/experimental features to users
- **beta_features_require_consent**: Boolean - Whether users must consent to use beta features
- **feature_rollout_percentage**: Integer (0-100) - Percentage of users to gradually roll out new features to

## Implementation Approach

### Settings Data Model
Expand the existing `ModuleSettingEntity` to handle more complex settings:
```
- id: UUID
- key: String (e.g., "workflow.draft_stage_sla_hours", "notification.email_enabled")
- group: String (for organizational purposes: "workflow", "notification", "integration.crm", etc.)
- subgroup: String (optional, for further organization)
- setting_type: ENUM (boolean, integer, float, string, json, encrypted_string, enum, array)
- value: String (stored as string, parsed according to setting_type)
- encrypted: Boolean (whether value contains sensitive data requiring encryption)
- description: String (human-readable description of what the setting does)
- default_value: String (the default value if not explicitly set)
- valid_values: String (JSON array for enum/array types, or min/max for numeric types)
- updated_at: Timestamp
- updated_by: UUID (user who last modified the setting)
```

### Settings Service Enhancements
1. **Type-Safe Accessors**: Methods like `getBooleanSetting()`, `getIntegerSetting()`, `getJsonSetting()` that handle parsing and default values
2. **Validation**: Comprehensive validation when settings are updated (range checking, enum validation, etc.)
3. **Caching**: Cache frequently accessed settings to reduce database load
4. **Change Detection**: Detect when critical settings change and trigger appropriate actions (e.g., reinitialize connections)
5. **Bulk Operations**: Support for exporting/importing settings as JSON for backup/migration

### UI Components
1. **Settings Organization**: Tabbed or accordion interface grouping settings by category
2. **Field Validation**: Real-time validation as users edit settings
3. **Help Text**: Contextual help explaining what each setting does
4. **Reset to Defaults**: Ability to reset individual settings or groups to factory defaults
5. **Search/Filter**: Ability to quickly find specific settings
6. **Bulk Edit**: Ability to apply changes to multiple similar settings
7. **Change History**: View who changed what and when (for audit purposes)

### API Endpoints
Enhance existing settings endpoints:
- `GET /api/settings` - Returns all settings organized by group
- `GET /api/settings/{group}` - Returns settings for specific group
- `GET /api/settings/{group}/{key}` - Returns specific setting value
- `PUT /api/settings/{group}/{key}` - Updates specific setting
- `POST /api/settings/reset` - Reset settings to defaults (with confirmation)
- `POST /api/settings/export` - Export settings as JSON backup
- `POST /api/settings/import` - Import settings from JSON backup (with validation)

### Default Values and Precedence
1. **Hardcoded Defaults**: Fallback values in code if no database value exists
2. **Database Values**: User-configured values stored in ModuleSettingEntity
3. **Environment Variables**: Ability to override via environment variables for deployment-specific values
4. **Tenants Overrides**: Ability to set different defaults per tenant in tenants-config
5. **Runtime Overrides**: Temporary overrides for debugging/testing (should not persist)

## Benefits of Expanded Admin Settings
1. **Operational Flexibility**: Adjust workflow behavior without code changes or deployments
2. **Tenant Customization**: Different tenants can tailor the module to their specific processes
3. **Compliance Adherence**: Configure settings to meet regulatory requirements (data retention, audit trails, etc.)
4. **Integration Readiness**: Prepare connections to external systems without touching code
5. **Performance Tuning**: Adjust parameters to optimize for specific usage patterns
6. **User Experience Optimization**: Customize interface and workflows for different user types
7. **Feature Management**: Safely enable/disable features and run A/B tests
8. **Reduced Support Burden**: Empower administrators to resolve configuration issues themselves
9. **Future-Proofing**: Easier to add new functionality as it's just adding new settings rather than changing core logic
10. **Consistency**: Standardized approach to configuration makes it easier for administrators to manage

## Implementation Considerations

### Migration from Existing Settings
1. Map existing `tools` and `match` groups to new structure
2. Provide backward compatibility during transition period
3. Consider automatic migration of existing values to new keys where appropriate

### Performance Implications
1. Increased database reads for settings access - implement caching strategy
2. More complex settings validation - may increase update time slightly
3. Larger settings table - ensure proper indexing on key/group columns

### Security Considerations
1. Encrypt sensitive values (passwords, tokens, keys) at rest
2. Audit all settings changes for security-relevant modifications
3. Validate that settings changes don't create security vulnerabilities
4. Consider implementing approval workflows for critical settings changes

### Usability Considerations
1. Group related settings logically to reduce cognitive load
2. Provide clear explanations and examples for complex settings
3. Implement progressive disclosure - show advanced settings only when requested
4. Consider validation that prevents nonsensical combinations of settings
5. Provide preview/simulation features for complex configurations (e.g., "What would happen if I change this setting?")

## Next Steps
1. Enhance agentic tools for workflow automation and external integrations
2. Identify required dependencies (CV/people DB, CRM, HRIS) and integration approaches