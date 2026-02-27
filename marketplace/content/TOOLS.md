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

### Firecrawl (via mcporter)

Web scraping and search powered by Firecrawl MCP server.

**Setup:**
```bash
mcporter list  # Show available MCP servers
mcporter call firecrawl.<tool> key=value  # Call a tool
```

**Available Tools (11):**

| Tool | Description |
|------|-------------|
| `firecrawl_search` | Web search with operators (like Google) |
| `firecrawl_scrape` | Extract content from URL (markdown/JSON/branding) |
| `firecrawl_map` | Discover all URLs on a site |
| `firecrawl_crawl` | Crawl multiple pages |
| `firecrawl_extract` | Structured data extraction with schema |
| `firecrawl_agent` | Autonomous research agent |
| `firecrawl_agent_status` | Check agent job status |
| `firecrawl_browser_create` | Create browser session (CDP) |
| `firecrawl_browser_delete` | Destroy browser session |
| `firecrawl_browser_list` | List active browser sessions |
| `firecrawl_check_crawl_status` | Check crawl job status |

**Usage Examples:**

```bash
# Search the web
mcporter call firecrawl.firecrawl_search query:"AI trends 2024" limit:5

# Scrape a URL (markdown)
mcporter call firecrawl.firecrawl_scrape url:"https://example.com/article" formats:["markdown"]

# Scrape structured data (JSON)
mcporter call firecrawl.firecrawl_scrape url:"https://example.com/pricing" formats:[{"type":"json","prompt":"Extract plan names and prices","schema":{"type":"object","properties":{"plans":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"price":{"type":"string"}}}}}}}]

# Map a site to find URLs
mcporter call firecrawl.firecrawl_map url:"https://example.com/blog"

# Run autonomous research
mcporter call firecrawl.firecrawl_agent prompt:"Find top AI tools for content writing"
```

**Search Operators:**
- `"phrase"` — Exact match
- `site:domain.com` — Limit to domain
- `-exclude` — Exclude term
- `intitle:word` — Word in title

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
| Web research | `mcporter call firecrawl_search` |
| Competitor analysis | `content-gap-analysis` skill |
| SEO articles | `seo-content-writer` skill |
| AI citation优化 | `geo-optimizer` skill |
| UX/marketing copy | `copywriter` skill |
| Scrape structured data | `mcporter call firecrawl_scrape` (JSON format) |