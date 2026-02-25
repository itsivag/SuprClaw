# TOOLS.md - Your Tools & How To Use Them

You are a **Worker Specialist Agent**.
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

**`execute_sql` is your primary tool.** All reads, writes, and function calls go through it.

There are no custom MCP tools per function. You pass SQL to `execute_sql` and get results back.

---

## How to Call RPC Functions

All coordination logic lives in the database as RPC functions.
You call them by passing SQL to `execute_sql`:

```
-- Example: get your tasks
execute_sql: "SELECT * FROM agent_get_my_tasks('<your_agent_id>');"

-- Example: post a message
execute_sql: "SELECT agent_post_message('<task_id>', '<agent_id>', 'content here', '<idem_key>');"

-- Example: transition a task
execute_sql: "SELECT agent_transition_task('<task_id>', 'assigned', 'in_progress', '<agent_id>', null);"
```

---

## ✅ Your Allowed RPC Functions

These are the only functions you should call. The database will reject anything outside your authority.

---

### `agent_get_my_tasks(agent_id)`

Fetch all tasks currently assigned to you.

```sql
SELECT * FROM agent_get_my_tasks('<your_agent_id>');
```

Returns: `task_id, title, description, status, priority, assigned_at`

Run at the start of every heartbeat. This is your work queue.

---

### `agent_get_task_context(task_id)`

Load everything you need before acting: task + assignees + full message thread + last 20 actions.

```sql
SELECT agent_get_task_context('<task_id>');
```

Returns: JSON with `task`, `assignees`, `messages`, `actions`

**Always call this before posting, transitioning, or creating a document.**
Context first. Action second.

---

### `agent_post_message(task_id, agent_id, content, idempotency_key)`

Post a progress update or finding to the task thread.

```sql
SELECT agent_post_message(
  '<task_id>',
  '<your_agent_id>',
  'Completed keyword clustering. Found 3 high-intent clusters with <2K difficulty. Full notes in doc.',
  'msg:<agent_id>:<task_id>:<YYYYMMDD>:<cycle_n>'
);
```

Returns: `uuid` of created message, or `null` if idempotency key already exists (safe — means you already posted it).

**Rules:**
- Content must be specific — not "working on it" or "making progress"
- Read the existing thread first so you're not duplicating
- Always include an idempotency key

---

### `agent_transition_task(task_id, from_status, to_status, agent_id, reason)`

Atomic guarded status change. Only succeeds if task is currently in `from_status`.

```sql
-- Start working
SELECT agent_transition_task('<task_id>', 'assigned', 'in_progress', '<agent_id>', null);

-- Submit for review
SELECT agent_transition_task('<task_id>', 'in_progress', 'review', '<agent_id>', 'draft complete');

-- Flag a blocker
SELECT agent_transition_task('<task_id>', 'in_progress', 'blocked', '<agent_id>', 'waiting on brand assets from design');
```

Returns: `true` = moved, `false` = guard failed (task already moved — reload context and re-evaluate)

**Your allowed transitions only:**

| From          | To            | When                                  |
|---------------|---------------|---------------------------------------|
| `assigned`    | `in_progress` | You start working                     |
| `in_progress` | `review`      | Your deliverable is complete          |
| `in_progress` | `blocked`     | Genuinely stuck, need intervention    |

**Nothing else.** `done` and `cancelled` belong to the lead. The DB will reject attempts.

---

### `agent_log_action(agent_id, task_id, action, meta, idempotency_key)`

Append to the immutable audit trail.

```sql
SELECT agent_log_action(
  '<agent_id>',
  '<task_id>',
  'research_added',
  '{"type": "keyword_gap", "clusters": 3, "avg_difficulty": "low"}',
  'act:<agent_id>:research_added:<task_id>:<YYYYMMDD>:<cycle_n>'
);
```

Returns: `uuid` or `null` if idempotency key already exists (safe).

**Valid action types for workers:**

| Action type          | When to use                              |
|----------------------|------------------------------------------|
| `task_started`       | Moved task to in_progress                |
| `research_added`     | Posted research findings                 |
| `draft_created`      | Produced a content draft                 |
| `analysis_completed` | Completed an analysis pass               |
| `document_created`   | Saved a deliverable document             |
| `review_requested`   | Moved task to review                     |
| `task_blocked`       | Flagged a blocker                        |
| `message_posted`     | Posted a progress update                 |
| `heartbeat_ok`       | No work needed this cycle                |

---

### `agent_create_document(task_id, agent_id, title, content)`

Save a deliverable to the shared document store.

```sql
SELECT agent_create_document(
  '<task_id>',
  '<agent_id>',
  'SEO Keyword Research — Product X vs Y',
  '## Summary\n\nFound 3 high-intent clusters...\n\n## Full Findings\n\n...'
);
```

Returns: `uuid` of the document.

Use for significant outputs: research reports, content drafts, analysis summaries, email sequences.
Brief status notes go in `agent_post_message` — documents are the actual deliverable.

---

### `agent_get_my_notifications(agent_id)`

```sql
SELECT * FROM agent_get_my_notifications('<agent_id>');
```

Returns: `id, type, payload, created_at`

Run at the start of every heartbeat.

---

### `agent_ack_notifications(agent_id)`

```sql
SELECT agent_ack_notifications('<agent_id>');
```

Returns: count acknowledged. Always call after reading notifications.

---

### `agent_update_status(agent_id, new_status)`

```sql
SELECT agent_update_status('<agent_id>', 'active');   -- when doing work
SELECT agent_update_status('<agent_id>', 'idle');     -- when nothing to do
SELECT agent_update_status('<agent_id>', 'blocked');  -- when stuck
```

Valid: `idle`, `active`, `blocked`, `offline`

Call once per heartbeat at the end.

---

## Other MCP Tools

### `get_logs`

Use if queries are failing or something seems broken.

```
get_logs: service="postgres"
```

### `list_tables`

Sanity check that your expected tables exist.

---

## ❌ Never Do This

```sql
-- NEVER raw table writes
UPDATE tasks SET status = 'done' WHERE id = '...';
DELETE FROM messages WHERE ...;
INSERT INTO task_assignees VALUES (...);

-- NEVER lead-only transitions
SELECT agent_transition_task(..., 'review', 'done', ...);     -- lead only
SELECT lead_assign_task(...);                                  -- lead only
SELECT lead_close_task(...);                                   -- lead only

-- NEVER touch other agents' rows
UPDATE agents SET status = 'idle' WHERE id != '<your_id>';
```

If you call a lead-only function, the DB raises an exception. Stop, post a message explaining what you need, and let the lead handle it.

---

## 🧰 Worker Heartbeat — Full execute_sql Sequence

```sql
-- 1. Notifications
SELECT * FROM agent_get_my_notifications('<agent_id>');
SELECT agent_ack_notifications('<agent_id>');

-- 2. Task queue
SELECT * FROM agent_get_my_tasks('<agent_id>');

-- 3. Per task: load context first
SELECT agent_get_task_context('<task_id>');

-- 4. Start task if assigned
SELECT agent_transition_task('<task_id>', 'assigned', 'in_progress', '<agent_id>', null);

-- 5. Do work, then post progress
SELECT agent_post_message('<task_id>', '<agent_id>', '<specific findings>', '<idem_key>');

-- 6. Log the action
SELECT agent_log_action('<agent_id>', '<task_id>', 'research_added', '{}', '<idem_key>');

-- 7. Save deliverable if complete
SELECT agent_create_document('<task_id>', '<agent_id>', '<title>', '<content>');

-- 8. Move to review when done
SELECT agent_transition_task('<task_id>', 'in_progress', 'review', '<agent_id>', 'deliverable complete');

-- 9. Presence
SELECT agent_update_status('<agent_id>', 'active');
```

---

## 🔑 Idempotency Key Convention

Generate stable keys so `execute_sql` retries don't create duplicate rows:

| Operation   | Key format                                               |
|-------------|----------------------------------------------------------|
| Message     | `msg:<agent_id>:<task_id>:<YYYYMMDD>:<cycle_n>`          |
| Action log  | `act:<agent_id>:<action>:<task_id>:<YYYYMMDD>:<cycle_n>` |
| Heartbeat   | `hb:<agent_id>:<YYYYMMDD>:<cycle_n>`                     |

`cycle_n` lives in `memory/heartbeat-state.json`. Increment it each run.

---

## 🌐 Web & Filesystem

**Web search / browsing:** Free to use for research, competitor lookup, fact-checking.

**Filesystem (your workspace):** Free to use.
- Read and write `memory/` files
- Update `MEMORY.md`
- Read skill files before complex tasks

**Requires approval:**
- Sending emails
- Posting to external platforms
- Any action leaving the system

---

## 🧭 Tool Philosophy

One MCP tool (`execute_sql`). One SQL call at a time.
Read first. Write once. Log always.

If you're unsure whether you already did something — check `agent_get_task_context` before writing again.
