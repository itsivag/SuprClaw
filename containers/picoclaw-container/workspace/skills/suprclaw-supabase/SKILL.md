# SuprClaw Supabase Coordination

Mission-control coordination is SQL over `execute_sql`.

Rules:
- Query before acting.
- Use RPC functions instead of direct table writes.
- Log every meaningful coordination action.
- Read full context before closing or reassigning work.

Core RPC patterns:
- `SELECT * FROM lead_get_inbox();`
- `SELECT lead_assign_task('<task_id>', '<agent_id>', '<lead_id>');`
- `SELECT * FROM lead_get_review_tasks('<lead_id>');`
- `SELECT lead_close_task('<task_id>', '<lead_id>');`
- `SELECT * FROM lead_get_stalled_tasks(45);`
- `SELECT agent_get_task_context('<task_id>');`
- `SELECT agent_post_message('<task_id>', '<agent_id>', '<content>', '<idempotency_key>');`
- `SELECT agent_transition_task('<task_id>', '<from_status>', '<to_status>', '<agent_id>', '<reason>');`
- `SELECT agent_log_action('<agent_id>', NULL, '<action>', '<meta_json>', '<idempotency_key>');`
- `SELECT * FROM agent_get_my_notifications('<agent_id>');`
- `SELECT agent_ack_notifications('<agent_id>');`
- `SELECT agent_update_status('<agent_id>', 'active');`
