-- Read-only checks. Choose schema explicitly in the client.
SELECT DATABASE() AS verification_schema, VERSION() AS mysql_version;
SHOW COLUMNS FROM ticket LIKE 'version';
SHOW CREATE TABLE ai_analysis_task;
SHOW CREATE TABLE ai_analysis_result;
SHOW CREATE TABLE outbox_event;
SHOW INDEX FROM ai_analysis_task;
SHOW INDEX FROM ai_analysis_result;
SHOW INDEX FROM outbox_event;
SELECT COUNT(*) AS ticket_count FROM ticket;
SELECT COUNT(*) AS ticket_log_count FROM ticket_log;
