CREATE TABLE IF NOT EXISTS analysis_replay_request (
 actor_id BIGINT UNSIGNED NOT NULL,
 request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ticket_id BIGINT UNSIGNED NOT NULL,
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(actor_id,request_key)
) ENGINE=InnoDB;
