# TOOLS.md - Your Tools & How To Use Them

Tools extend your reach. Use them with precision.

You are the **Lead Coordinator Agent**.
All database coordination happens through **Supabase MCP**.

---

## How Supabase MCP Actually Works

The Supabase MCP server exposes a small fixed set of tools.
The ones you will use are:

| MCP Tool       | What it does                           |
|----------------|----------------------------------------|
| `execute_sql`  | Run any SQL against the database       |
| `get_logs`     | Retrieve Postgres / API logs           |
| `get_advisors` | Security and performance warnings      |
| `list_tables`  | See what tables exist                  |

**`execute_sql` is your primary tool.** Everything — reads, writes, RPC calls — goes through it.

There are no custom MCP tools per function. You pass SQL to `execute_sql` and get results back.

---

## How to Call RPC Functions

All coordination logic lives in the database as RPC functions (defined in the schema).
You invoke them by passing SQL to `execute_sql`:

```
-- Example: fetch inbox
execute_sql: "SELECT * FROM lead_get_inbox();"

-- Example: assign a task
execute_sql: "SELECT lead_assign_task('<task_id>', '<agent_id>', '<lead_id>');"

-- Example: update status
execute_sql: "SELECT agent_update_status('<lead_id>', 'active');"
```

SQL in, results out. That's the whole interface.

---

## Lead-Only RPC Functions

These raise a Postgres exception if called by a non-lead agent.
The database enforces this — not just convention.

---

### `lead_get_inbox()`

Fetch all unassigned tasks ready to be assigned.

```sql
SELECT * FROM lead_get_inbox();
```

Returns: `task_id, title, description, priority, created_at`

Run at the start of every heartbeat.

---

### `lead_assign_task(task_id, agent_id, lead_id)`

Assign a worker to a task. Idempotent — returns `false` if already assigned, no error.

```sql
SELECT lead_assign_task('<task_id>', '<agent_id>', '<your_lead_id>');
```

Returns: `true` = assigned, `false` = already assigned (safe no-op)

The DB trigger fires automatically — worker gets a notification. You don't need to create it.

---

### `lead_get_review_tasks(lead_id)`

Tasks in `review` or `blocked` waiting for your action.

```sql
SELECT * FROM lead_get_review_tasks('<lead_id>');
```

Returns: `task_id, title, status, last_message, message_at, assignees`

---

### `lead_close_task(task_id, lead_id)`

Mark a reviewed task as done. Internally validates task is in `review`.

```sql
SELECT lead_close_task('<task_id>', '<lead_id>');
```

Returns: `true` = closed, `false` = guard failed (task not in review state)

**Always read task context first. Never close blind.**

---

### `lead_get_stalled_tasks(minutes_threshold)`

Tasks in progress with no recent activity.

```sql
SELECT * FROM lead_get_stalled_tasks(45);
```

Returns: `task_id, title, status, last_action_at, minutes_stale, assignees`

---

## Shared RPC Functions (Lead + Workers)

---

### `agent_get_task_context(task_id)`

Full context for a task: task data + assignees + full message thread + last 20 actions.

```sql
SELECT agent_get_task_context('<task_id>');
```

Returns: JSON with `task`, `assignees`, `messages`, `actions`

**Always call before closing or posting to a task.**

---

### `agent_post_message(task_id, agent_id, content, idempotency_key)`

Post a coordination note to the task thread.

```sql
SELECT agent_post_message(
  '<task_id>',
  '<lead_id>',
  'Returning for revision — comparison section needs citations.',
  'msg:<lead_id>:<task_id>:<YYYYMMDD>:<cycle_n>'
);
```

Returns: `uuid` of created message, or `null` if idempotency key already exists (safe).

---

### `agent_transition_task(task_id, from_status, to_status, agent_id, reason)`

Atomic guarded status change. Only succeeds if task is currently in `from_status`.

```sql
SELECT agent_transition_task(
  '<task_id>',
  'review',
  'in_progress',
  '<lead_id>',
  'returned for revision: missing sources'
);
```

Returns: `true` = moved, `false` = guard failed

**Lead-allowed transitions:**

| From      | To            | Use case                    |
|-----------|---------------|-----------------------------|
| `review`  | `in_progress` | Return for revision         |
| `review`  | `blocked`     | Blocked before close        |
| `blocked` | `assigned`    | Unblock a stuck task        |
| any       | `cancelled`   | Scrap the task              |

For `review → done` use `lead_close_task()` instead — it also cleans up agent state.

---

### `agent_log_action(agent_id, task_id, action, meta, idempotency_key)`

Append to the immutable audit trail.

```sql
SELECT agent_log_action(
  '<lead_id>',
  NULL,
  'heartbeat_ok',
  '{"inbox_cleared": 2, "tasks_closed": 1}',
  'hb:<lead_id>:<YYYYMMDD>:<cycle_n>'
);
```

Valid action types for lead:
`task_assigned`, `task_reassigned`, `task_finished`, `task_cancelled`,
`task_resumed`, `message_posted`, `heartbeat_ok`, `stale_lock_cleared`

---

### `agent_get_my_notifications(agent_id)`

```sql
SELECT * FROM agent_get_my_notifications('<lead_id>');
```

Returns: `id, type, payload, created_at`

---

### `agent_ack_notifications(agent_id)`

```sql
SELECT agent_ack_notifications('<lead_id>');
```

Returns: count acknowledged. Always call after reading notifications.

---

### `agent_update_status(agent_id, new_status)`

```sql
SELECT agent_update_status('<lead_id>', 'active');
SELECT agent_update_status('<lead_id>', 'idle');
```

Valid: `idle`, `active`, `blocked`, `offline`

---

## Other MCP Tools You Can Use

### `get_logs`

Diagnose broken behaviour — agent not waking, queries failing, etc.

### `get_advisors`

Occasional schema / security health check.

### `list_tables`

Quick sanity check that all tables exist and are reachable.

---

## Never Do This

```sql
-- NEVER raw table writes — use the RPC functions
UPDATE tasks SET status = 'done' WHERE id = '...';
DELETE FROM notifications WHERE ...;
INSERT INTO task_assignees VALUES (...);

-- NEVER destructive schema changes during operation
DROP TABLE ...;
TRUNCATE agent_actions;
ALTER TABLE ...;
```

---

## Lead Heartbeat — Full execute_sql Sequence

```sql
-- 1. Notifications
SELECT * FROM agent_get_my_notifications('<lead_id>');
SELECT agent_ack_notifications('<lead_id>');

-- 2. Inbox
SELECT * FROM lead_get_inbox();
SELECT lead_assign_task('<task_id>', '<agent_id>', '<lead_id>');

-- 3. Review queue
SELECT * FROM lead_get_review_tasks('<lead_id>');
SELECT agent_get_task_context('<task_id>');
SELECT lead_close_task('<task_id>', '<lead_id>');

-- 4. Stall detection
SELECT * FROM lead_get_stalled_tasks(45);

-- 5. Presence + heartbeat log
SELECT agent_update_status('<lead_id>', 'active');
SELECT agent_log_action('<lead_id>', NULL, 'heartbeat_ok', '{}', 'hb:<lead_id>:<YYYYMMDD>:<cycle_n>');
```

---

## Idempotency Key Convention

| Operation  | Key format                                               |
|------------|----------------------------------------------------------|
| Message    | `msg:<agent_id>:<task_id>:<YYYYMMDD>:<cycle_n>`          |
| Action log | `act:<agent_id>:<action>:<task_id>:<YYYYMMDD>:<cycle_n>` |
| Heartbeat  | `hb:<agent_id>:<YYYYMMDD>:<cycle_n>`                     |

`cycle_n` comes from `memory/heartbeat-state.json`. Increment it each run.

---

## Tool Philosophy

One MCP tool (`execute_sql`). One SQL statement at a time.
Read the state, make the call, log it, move on.
