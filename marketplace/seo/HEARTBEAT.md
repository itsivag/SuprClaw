# HEARTBEAT.md — Worker Execution Protocol

Run in order. Every heartbeat.
DB state = truth. Never rely on memory.

---

## Pre-Flight

* Resolve `agent_id` (if not cached)
* Capture timestamp + cycle number (for idempotency keys)

---

## 1 — Check Notifications

```
select * from agent_get_my_notifications('<agent_id>');
select agent_ack_notifications('<agent_id>');
```

Notifications signal where to look — not what to do.

---

## 2 — Fetch Assigned Tasks

```
select * from agent_get_my_tasks('<agent_id>');
```

If none → go to Final Step.
Only work on tasks assigned to you.

---

## 3 — Process Each Task (Priority Order)

### 3a — Load Context

```
select agent_get_task_context('<task_id>');
```

Read:

* Task details
* All messages
* Recent actions

Never skip context.

---

### 3b — Duplicate Guard

Before acting:

* Did I already do this this cycle?
* Is there already a log entry?
* Is this finding already posted?

If yes → skip.

---

### 3c — Start Task (If Needed)

If status = `assigned`:

```
select agent_transition_task('<task_id>', 'assigned', 'in_progress', '<agent_id>', null);
```

If false → re-check context.

---

### 3d — Do Real Work

One meaningful step per cycle is fine.
Depth > breadth. Concrete output > vague updates.

Real work = tangible deliverable progress.
“Thinking about it” ≠ work.

---

### 3e — Post Progress

```
select agent_post_message('<task_id>', '<agent_id>', '<summary>', '<idem_key>');
```

Message must be:

* Specific
* Self-contained
* Honest

Idem key:
`msg:<agent_id>:<task_id>:<YYYYMMDD>:<cycle_n>`

---

### 3f — Log Action

```
select agent_log_action('<agent_id>', '<task_id>', '<action_type>', '<meta_json>', '<idem_key>');
```

Common types:

* `research_added`
* `draft_created`
* `analysis_completed`
* `message_posted`
* `review_requested`
* `task_blocked`

Idem key:
`act:<agent_id>:<action_type>:<task_id>:<YYYYMMDD>:<cycle_n>`

---

### 3g — Save Deliverable (If Created)

```
select agent_create_document('<task_id>', '<agent_id>', '<title>', '<content>');
```

Documents = output.
Messages = commentary.

---

### 3h — Request Review (If Complete)

```
select agent_transition_task('<task_id>', 'in_progress', 'review', '<agent_id>', 'deliverable complete');
```

Post summary message.
You do not close tasks.

---

### 3i — Block If Necessary

If genuinely stuck:

```
select agent_transition_task('<task_id>', 'in_progress', 'blocked', '<agent_id>', 'reason');
select agent_post_message('<task_id>', '<agent_id>', 'Blocked: [specific reason + need]', '<idem_key>');
```

Be specific.

---

## 4 — Update Presence

```
select agent_update_status('<agent_id>', 'active');  -- if worked
select agent_update_status('<agent_id>', 'idle');    -- if no work
```

---

## 5 — Update Memory

* Add key findings → `memory/YYYY-MM-DD.md`
* Add durable lessons → `MEMORY.md`

---

## Final Step — Output Decision

Return `HEARTBEAT_OK` if:

* No tasks
* No notifications
* No new progress this cycle

Return brief summary only if real work was done.

No filler.

---

## Heartbeat State Tracking

`memory/heartbeat-state.json`

```json
{
  "lastChecks": {
    "notifications": 0,
    "tasks": 0,
    "last_cycle": ""
  },
  "cycleCount": 0
}
```

Increment `cycleCount` each heartbeat.
Use it in idempotency keys.

---

## Never During Heartbeat

* Assign tasks
* Move tasks to `done` or `cancelled`
* Modify other agents
* Run raw SQL
* Post duplicates
* Use unstable idempotency keys

---

Real work. Verified state. Deterministic writes.
