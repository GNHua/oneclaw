# FEAT-006: Token/Cost Tracking - Discussion Notes

## Status: Not yet discussed in detail. Waiting for detailed PRD writing.

## What we know from overview discussions:
- Users use their own API keys, so usage visibility is important
- Display token count per message (input/output tokens)
- Display token usage per session
- Cumulative usage statistics (daily, weekly, monthly, all-time)
- Cost estimation based on model pricing

## Open questions to discuss:
1. **Cost pricing data**: Where do we get model pricing? Hard-code known prices? Let user configure? Fetch from an API? Prices change frequently.
2. **Currency**: Display costs in USD? Or let user pick currency?
3. **Usage dashboard**: Is a separate dashboard/stats screen needed? Or just show usage inline in chat and session list?
4. **Budget/alerts**: Should we support budget limits or spending alerts? (e.g., "You've spent $5 today")
5. **Title generation cost**: The lightweight model calls for session title generation (FEAT-005) -- should these be tracked separately or lumped into the session's usage?
6. **Per-agent usage**: Track usage per Agent? Could be useful to see which Agent costs the most.
7. **Export usage data**: Should users be able to export their usage stats (CSV, etc.)?
8. **Token counting accuracy**: Different providers report tokens differently. Some don't report input tokens in streaming. How do we handle inconsistencies?
