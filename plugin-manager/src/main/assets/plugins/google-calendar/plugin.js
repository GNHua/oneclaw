var CALENDAR_API = "https://www.googleapis.com/calendar/v3";

async function getToken() {
    if (typeof oneclaw.google === "undefined") {
        throw new Error("Google auth not available. Connect your Google account in Settings.");
    }
    var token = await oneclaw.google.getAccessToken();
    if (!token) {
        throw new Error("Not signed in to Google. Connect your Google account in Settings.");
    }
    return token;
}

async function calFetch(method, path, body) {
    var token = await getToken();
    var headers = { "Authorization": "Bearer " + token };
    var raw = await oneclaw.http.fetch(
        method,
        CALENDAR_API + path,
        body || null,
        "application/json",
        headers
    );
    var resp = JSON.parse(raw);
    if (resp.error) {
        throw new Error("Calendar request failed: " + resp.error);
    }
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

function buildTimeObj(dt, tz) {
    if (isDateOnly(dt)) {
        return { date: dt };
    }
    var obj = { dateTime: dt };
    // If datetime lacks offset/Z, attach calendar timezone so Google API can interpret it
    if (!/[Zz]$/.test(dt) && !/[+\-]\d{2}:\d{2}$/.test(dt) && tz) {
        obj.timeZone = tz;
    }
    return obj;
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
                if (!args.start || !args.end) {
                    return { error: "Missing required parameters 'start' and 'end'. Use ISO 8601 with timezone offset (e.g. '2026-02-20T14:00:00-08:00') or date-only (e.g. '2026-02-20') for all-day events." };
                }

                // Fetch calendar timezone to fill in bare datetimes
                var tz = "";
                if (!isDateOnly(args.start)) {
                    try {
                        var calMeta = await calFetch("GET", "/calendars/" + calId);
                        tz = calMeta.timeZone || "";
                    } catch (e) { /* proceed without tz */ }
                }

                var event = {
                    summary: args.summary,
                    start: buildTimeObj(args.start, tz),
                    end: buildTimeObj(args.end, tz)
                };

                if (args.description) event.description = args.description;
                if (args.location) event.location = args.location;
                if (args.event_type) event.eventType = args.event_type;
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

                if (args.start || args.end) {
                    var tz = "";
                    try {
                        var calMeta = await calFetch("GET", "/calendars/" + calId);
                        tz = calMeta.timeZone || "";
                    } catch (e) { /* proceed without tz */ }
                    if (args.start) {
                        existing.start = buildTimeObj(args.start, tz);
                    }
                    if (args.end) {
                        existing.end = buildTimeObj(args.end, tz);
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

            case "calendar_freebusy": {
                var items = (args.calendar_ids || ["primary"]).map(function(id) {
                    return { id: id };
                });
                var data = await calFetch("POST", "/freeBusy",
                    JSON.stringify({
                        timeMin: args.time_min,
                        timeMax: args.time_max,
                        items: items
                    }));
                var result = {};
                var calendars = data.calendars || {};
                for (var calId in calendars) {
                    result[calId] = {
                        busy: (calendars[calId].busy || []).map(function(b) {
                            return { start: b.start, end: b.end };
                        }),
                        errors: calendars[calId].errors || []
                    };
                }
                return { output: JSON.stringify(result, null, 2) };
            }

            case "calendar_respond": {
                var calId = encodeURIComponent(args.calendar_id || "primary");
                var eventId = encodeURIComponent(args.event_id);
                var event = await calFetch("GET",
                    "/calendars/" + calId + "/events/" + eventId);

                var email = await oneclaw.google.getAccountEmail();
                var attendees = event.attendees || [];
                var found = false;
                for (var i = 0; i < attendees.length; i++) {
                    if (attendees[i].email === email || attendees[i].self) {
                        attendees[i].responseStatus = args.response;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    attendees.push({ email: email, responseStatus: args.response });
                }
                event.attendees = attendees;

                var data = await calFetch("PUT",
                    "/calendars/" + calId + "/events/" + eventId,
                    JSON.stringify(event));
                return { output: "Responded '" + args.response + "' to event: " + data.summary };
            }

            case "calendar_list_colors": {
                var data = await calFetch("GET", "/colors");
                return { output: JSON.stringify(data, null, 2) };
            }

            case "calendar_quick_add": {
                var rawCalId = args.calendar_id || "primary";
                var calId = encodeURIComponent(rawCalId);
                // Fetch calendar timezone so quickAdd interprets times correctly
                var calMeta = await calFetch("GET", "/calendars/" + calId);
                var tz = calMeta.timeZone || "";
                var text = args.text;
                if (tz) {
                    text = text + " (" + tz + ")";
                }
                var data = await calFetch("POST",
                    "/calendars/" + calId + "/events/quickAdd?text=" +
                    encodeURIComponent(text));
                return {
                    output: "Event created: " + (data.summary || args.text) +
                        "\nID: " + data.id +
                        "\nStart: " + (data.start ? (data.start.dateTime || data.start.date) : "") +
                        "\nLink: " + (data.htmlLink || "")
                };
            }

            case "calendar_instances": {
                var calId = encodeURIComponent(args.calendar_id || "primary");
                var eventId = encodeURIComponent(args.event_id);
                var maxResults = Math.min(args.max_results || 25, 100);
                var path = "/calendars/" + calId + "/events/" + eventId + "/instances?" +
                    "maxResults=" + maxResults;
                if (args.time_min) path += "&timeMin=" + encodeURIComponent(args.time_min);
                if (args.time_max) path += "&timeMax=" + encodeURIComponent(args.time_max);
                var data = await calFetch("GET", path);
                var instances = (data.items || []).map(formatEvent);
                return { output: JSON.stringify(instances, null, 2) };
            }

            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        var msg = (e && e.message) ? e.message : String(e || "Unknown error");
        oneclaw.log.error("calendar error: " + msg);
        return { error: msg };
    }
}
