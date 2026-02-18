---
name: morning-briefing
description: Daily briefing combining calendar, email, tasks, and weather
---

Compile a concise morning briefing for the user. Gather information from multiple sources, then present a single unified summary.

## Data to gather

Activate the relevant tool categories first (use `activate_tools`), then collect:

1. **Calendar** -- Use `calendar_list_events` to get today's events. Note start times, titles, and any conflicts.
2. **Email** -- Use `gmail_search` with query `is:unread newer_than:1d` to get recent unread emails. Note sender, subject, and urgency.
3. **Tasks** -- Use `tasks_list_tasks` to get pending tasks. Note due dates.
4. **Weather** -- Use `http_get` to call the Open-Meteo API for the user's location:
   - Geocode: `https://geocoding-api.open-meteo.com/v1/search?name={city}&count=1&language=en&format=json`
   - Weather: `https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&current=temperature_2m,apparent_temperature,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=1`

## Output format

Present as a single, scannable briefing:

**Weather** -- one line (temp, condition, precipitation chance)

**Schedule** -- list of today's events with times, highlight conflicts or back-to-back meetings

**Email** -- count of unread, highlight important/urgent senders (skip newsletters and automated notifications)

**Tasks** -- list overdue and due-today items

**Heads up** -- any notable items (meeting conflicts, high-priority emails, overdue tasks)

## Guidelines

- If any data source fails (e.g., Gmail not authenticated), skip it and note it at the end rather than blocking the entire briefing.
- If the user's location is not known for weather, ask once and suggest they save it to memory for future briefings.
- Keep the entire briefing short enough to read on a phone screen in under 30 seconds.
