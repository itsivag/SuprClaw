# TOOLS.md - Skills & External Tools

## Content Writing Skills

Located in `/skills/`

| Skill | Purpose |
|-------|---------|
| `copywriter` | UX/marketing copy — buttons, CTAs, emails, landing pages, error messages |
| `geo-optimizer` | Optimize content for AI citation (ChatGPT, Claude, Perplexity) |
| `content-gap-analysis` | Find competitor content gaps and opportunities |
| `seo-content-writer` | Write SEO-optimized blog posts and articles |
| `memory-setup` | Memory/recall system for agent continuity |

---

## External Tools

### SuprClaw Cloud Browser

Interactive browsing powered by the SuprClaw cloud browser runtime tools.

**Available Tools:**

| Tool | Description |
|------|-------------|
| `cloud_browser_list_profiles` | List available SuprClaw browser profiles |
| `cloud_browser_create_profile` | Create a SuprClaw browser profile explicitly |
| `cloud_browser_open` | Create a SuprClaw-managed browser session |
| `cloud_browser_reset_profile` | Reset a browser profile to a clean identity |
| `cloud_browser_exec` | Navigate, inspect, click, type, and capture browser state |
| `cloud_browser_request_takeover` | Ask the user to take over for CAPTCHA, MFA, login approvals, or sensitive actions |
| `cloud_browser_resume` | Return control to the agent after user takeover |
| `cloud_browser_close` | Gracefully close the browser session |

**Usage Examples:**

Example workflow:
- Open a browser session for the current research task.
- List or create profiles when you need stable named browser identities.
- Navigate, inspect, and snapshot pages with `cloud_browser_exec`.
- Ask the user to take over for CAPTCHA, MFA, login approval, or destructive actions.

**Research Guidance:**
- Use built-in web fetch/search tools first for read-only research.
- Use `cloud_browser_*` when the site requires interaction, authenticated state, or a live browser view for the user.
- Always request takeover for CAPTCHA, MFA, payments, account changes, or destructive actions.

---

## Skill Usage Guidelines

1. **copywriter** → UI text, marketing copy, CTAs
2. **geo-optimizer** → Before publishing: audit for AI citation
3. **content-gap-analysis** → Research phase: find opportunities
4. **seo-content-writer** → Draft phase: write optimized content
5. **memory-setup** → Recall prior work, preferences, context

---

## Quick Reference

| Need | Tool/Skill |
|------|------------|
| Interactive web research | `cloud_browser_open` + `cloud_browser_exec` |
| Competitor analysis | `content-gap-analysis` skill |
| SEO articles | `seo-content-writer` skill |
| AI citation优化 | `geo-optimizer` skill |
| UX/marketing copy | `copywriter` skill |
| Live browsing with user takeover | `cloud_browser.cloud_browser_request_takeover` |
