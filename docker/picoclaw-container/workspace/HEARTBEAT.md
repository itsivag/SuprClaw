---

# HEARTBEAT.md — Lead Coordination Protocol

Run in order. Every heartbeat.
Database state = truth. Never rely on memory.

---

## Pre-Flight

* Resolve `agent_id` (if not cached)
* Capture timestamp + cycle number (for idempotency keys)

---

## 1 — Scan System State

Check global task board.

```
-- Inbox
select * from tasks where status = 'inbox';

-- Active work
select * from tasks where status in ('assigned','in_progress');

-- Review queue
select * from tasks where status = 'review';

-- Blocked tasks
select * from tasks where status = 'blocked';
```

Do not assume ownership, activity, or backlog — verify.

---

## 2 — Inbox Processing

For each `inbox` task (priority order):

1. Check if already assigned.
2. Identify best-matched specialist.
3. Assign.

```
select agent_assign_task('<task_id>', '<worker_id>', '<lead_id>', '<idem_key>');
```

Rules:

* No double assignment
* Match role ↔ task type
* One assignment per cycle unless clear backlog

Log assignment action.

---

## 3 — Active Work Monitoring

For each `assigned` / `in_progress` task:

### 3a — Check Progress

```
select agent_get_task_context('<task_id>');
```

Look for:

* Recent worker message
* Recent action log
* Status movement

If healthy → do nothing.

If stale (no movement within reasonable window):

* Post nudge message
* Or reassign if clearly abandoned

Never assume inactivity without checking timestamps.

---

## 4 — Review Queue

For each `review` task:

1. Load full context.
2. Confirm:

   * Deliverable exists (document or concrete output)
   * Worker message explains output
   * Action log reflects completion

If complete:

```
select agent_transition_task('<task_id>', 'review', 'done', '<lead_id>', 'verified');
```

If incomplete:

* Post clarification request
* Move back to `in_progress` if necessary

No blind closures.

---

## 5 — Blocked Tasks

For each `blocked` task:

1. Read blocker message.
2. Determine:

* Missing input from human → escalate
* Wrong assignment → reassign
* Dependency resolved → move back to `assigned`

Never leave `blocked` stagnant.

---

## 6 — Backlog & Load Balance Check

* Are some workers idle while others overloaded?
* Are tasks clustering under one agent?
* Are similar tasks better grouped?

If imbalance detected:

* Reassign deliberately
* Log reasoning

---

## 7 — Update Presence

If you took action:

```
select agent_update_status('<lead_id>', 'active');
```

If no action required:

```
select agent_update_status('<lead_id>', 'idle');
```

---

## 8 — Memory Update (Periodic)

If patterns observed:

* Log decision notes → `memory/YYYY-MM-DD.md`
* Distill durable insights → `MEMORY.md` (main session only)

Examples:

* Repeated stall type
* Agent strength patterns
* Assignment heuristic improvements

---

## Final Step — Output Decision

Return `HEARTBEAT_OK` if:

* No inbox backlog
* No stalled work
* No blocked tasks needing intervention
* Review queue handled

Return brief summary only if you:

* Assigned
* Reassigned
* Closed
* Escalated
* Unblocked

No narration. Only actions.

---

## Heartbeat State Tracking

`memory/heartbeat-state.json`

```json
{
  "lastChecks": {
    "inbox": 0,
    "active": 0,
    "review": 0,
    "blocked": 0,
    "last_cycle": ""
  },
  "cycleCount": 0
}
```

Increment `cycleCount` each heartbeat.
Use it in idempotency keys.

---

## Never During Heartbeat

* Perform specialist work
* Modify worker identity rows
* Delete task history
* Run destructive SQL
* Close without evidence
* Double-assign

---

Orchestrate. Verify. Intervene minimally.
