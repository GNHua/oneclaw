var CALENDAR_API = "https://www.googleapis.com/calendar/v3";

async function getToken() {
    if (typeof palmclaw.google === "undefined") {
        throw new Error("Google auth not available. Connect your Google account in Settings.");
    }
    var token = await palmclaw.google.getAccessToken();
    if (!token) {
        throw new Error("Not signed in to Google. Connect your Google account in Settings.");
    }
    return token;
}

async function calFetch(method, path, body) {
    var token = await getToken();
    var headers = { "Authorization": "Bearer " + token };
    var raw = await palmclaw.http.fetch(
        method,
        CALENDAR_API + path,
        body || null,
        "application/json",
        headers
    );
    var resp = JSON.parse(raw);
    if (resp.status >= 400) {
        throw new Error("Calendar API error (HTTP " + resp.status + "): " + resp.body);
    }
    if (!resp.body || resp.body.length === 0) return {};
    return JSON.parse(resp.body);
}

function formatEvent(event) {
    var start = event.start
        ? (event.start.dateTime || event.start.date || "")
        : "";
    var end = event.end
        ? (event.end.dateTime || event.end.date || "")
        : "";
    return {
        id: event.id,
        summary: event.summary || "(no title)",
        start: start,
        end: end,
        location: event.location || "",
        description: event.description || "",
        status: event.status || "",
        htmlLink: event.htmlLink || ""
    };
}

function isDateOnly(str) {
    return /^\d{4}-\d{2}-\d{2}$/.test(str);
}

async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "calendar_list_events": {
                var calId = encodeURIComponent(args.calendar_id || "primary");
                var now = new Date().toISOString();
                var weekLater = new Date(Date.now() + 7 * 86400000).toISOString();
                var timeMin = args.time_min || now;
                var timeMax = args.time_max || weekLater;
                var maxResults = Math.min(args.max_results || 25, 100);

                var path = "/calendars/" + calId + "/events?" +
                    "timeMin=" + encodeURIComponent(timeMin) +
                    "&timeMax=" + encodeURIComponent(timeMax) +
                    "&maxResults=" + maxResults +
                    "&singleEvents=true" +
                    "&orderBy=startTime";

                if (args.query) {
                    path += "&q=" + encodeURIComponent(args.query);
                }

                var data = await calFetch("GET", path);
                var events = (data.items || []).map(formatEvent);

                if (events.length === 0) {
                    return { output: "No events found in the specified time range." };
                }

                return { output: JSON.stringify(events, null, 2) };
            }

            case "calendar_get_event": {
                var calId = encodeURIComponent(args.calendar_id || "primary");
                var data = await calFetch("GET",
                    "/calendars/" + calId + "/events/" + encodeURIComponent(args.event_id));

                return { output: JSON.stringify(formatEvent(data), null, 2) };
            }

            case "calendar_create_event": {
                var calId = encodeURIComponent(args.calendar_id || "primary");
                var event = {
                    summary: args.summary
                };

                if (isDateOnly(args.start)) {
                    event.start = { date: args.start };
                    event.end = { date: args.end };
                } else {
                    event.start = { dateTime: args.start };
                    event.end = { dateTime: args.end };
                }

                if (args.description) event.description = args.description;
                if (args.location) event.location = args.location;
                if (args.attendees) {
                    event.attendees = args.attendees.split(",").map(function(email) {
                        return { email: email.trim() };
                    });
                }

                var data = await calFetch("POST",
                    "/calendars/" + calId + "/events",
                    JSON.stringify(event));

                return {
                    output: "Event created: " + data.summary +
                        "\nID: " + data.id +
                        "\nLink: " + (data.htmlLink || "")
                };
            }

            case "calendar_update_event": {
                var calId = encodeURIComponent(args.calendar_id || "primary");
                var eventId = encodeURIComponent(args.event_id);

                var existing = await calFetch("GET",
                    "/calendars/" + calId + "/events/" + eventId);

                if (args.summary) existing.summary = args.summary;
                if (args.description !== undefined) existing.description = args.description;
                if (args.location !== undefined) existing.location = args.location;
                if (args.start) {
                    if (isDateOnly(args.start)) {
                        existing.start = { date: args.start };
                    } else {
                        existing.start = { dateTime: args.start };
                    }
                }
                if (args.end) {
                    if (isDateOnly(args.end)) {
                        existing.end = { date: args.end };
                    } else {
                        existing.end = { dateTime: args.end };
                    }
                }

                var data = await calFetch("PUT",
                    "/calendars/" + calId + "/events/" + eventId,
                    JSON.stringify(existing));

                return { output: "Event updated: " + data.summary };
            }

            case "calendar_delete_event": {
                var calId = encodeURIComponent(args.calendar_id || "primary");
                await calFetch("DELETE",
                    "/calendars/" + calId + "/events/" + encodeURIComponent(args.event_id));
                return { output: "Event deleted successfully." };
            }

            case "calendar_list_calendars": {
                var data = await calFetch("GET", "/users/me/calendarList");
                var calendars = (data.items || []).map(function(cal) {
                    return {
                        id: cal.id,
                        summary: cal.summary,
                        primary: cal.primary || false,
                        accessRole: cal.accessRole
                    };
                });
                return { output: JSON.stringify(calendars, null, 2) };
            }

            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        palmclaw.log.error("calendar error: " + e.message);
        return { error: e.message };
    }
}
