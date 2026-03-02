/**
 * Google Calendar tool group for OneClawShadow.
 */

var CALENDAR_API = "https://www.googleapis.com/calendar/v3";

async function calendarFetch(method, path, body) {
    var token = await google.getAccessToken();
    var options = {
        method: method,
        headers: {
            "Authorization": "Bearer " + token,
            "Content-Type": "application/json"
        }
    };
    if (body) {
        options.body = JSON.stringify(body);
    }
    var resp = await fetch(CALENDAR_API + path, options);
    if (!resp.ok) {
        var errorText = await resp.text();
        throw new Error("Calendar API error (" + resp.status + "): " + errorText);
    }
    if (resp.status === 204) return { success: true };
    return await resp.json();
}

async function calendarListCalendars(params) {
    var data = await calendarFetch("GET", "/users/me/calendarList");
    return { calendars: data.items || [] };
}

async function calendarListEvents(params) {
    var calendarId = encodeURIComponent(params.calendar_id);
    var query = "?singleEvents=true&orderBy=startTime";
    if (params.time_min) query += "&timeMin=" + encodeURIComponent(params.time_min);
    if (params.time_max) query += "&timeMax=" + encodeURIComponent(params.time_max);
    if (params.max_results) query += "&maxResults=" + params.max_results;
    if (params.query) query += "&q=" + encodeURIComponent(params.query);

    var data = await calendarFetch("GET", "/calendars/" + calendarId + "/events" + query);
    return { events: data.items || [], nextPageToken: data.nextPageToken };
}

async function calendarGetEvent(params) {
    var calendarId = encodeURIComponent(params.calendar_id);
    return await calendarFetch("GET", "/calendars/" + calendarId + "/events/" + params.event_id);
}

async function calendarCreateEvent(params) {
    var calendarId = encodeURIComponent(params.calendar_id);
    var event = {
        summary: params.summary
    };
    if (params.description) event.description = params.description;
    if (params.location) event.location = params.location;

    if (params.all_day) {
        event.start = { date: params.start };
        event.end = { date: params.end };
    } else {
        var tz = params.timezone || "UTC";
        event.start = { dateTime: params.start, timeZone: tz };
        event.end = { dateTime: params.end, timeZone: tz };
    }

    if (params.attendees && params.attendees.length > 0) {
        event.attendees = params.attendees.map(function(email) {
            return { email: email };
        });
    }

    return await calendarFetch("POST", "/calendars/" + calendarId + "/events", event);
}

async function calendarUpdateEvent(params) {
    var calendarId = encodeURIComponent(params.calendar_id);
    var existing = await calendarFetch("GET", "/calendars/" + calendarId + "/events/" + params.event_id);

    if (params.summary) existing.summary = params.summary;
    if (params.description !== undefined) existing.description = params.description;
    if (params.location !== undefined) existing.location = params.location;
    if (params.start) {
        var tz = params.timezone || existing.start.timeZone || "UTC";
        existing.start = { dateTime: params.start, timeZone: tz };
    }
    if (params.end) {
        var tz = params.timezone || existing.end.timeZone || "UTC";
        existing.end = { dateTime: params.end, timeZone: tz };
    }

    return await calendarFetch("PUT", "/calendars/" + calendarId + "/events/" + params.event_id, existing);
}

async function calendarDeleteEvent(params) {
    var calendarId = encodeURIComponent(params.calendar_id);
    var token = await google.getAccessToken();
    var resp = await fetch(CALENDAR_API + "/calendars/" + calendarId + "/events/" + params.event_id, {
        method: "DELETE",
        headers: { "Authorization": "Bearer " + token }
    });
    if (!resp.ok && resp.status !== 204) {
        var errorText = await resp.text();
        throw new Error("Calendar API error (" + resp.status + "): " + errorText);
    }
    return { success: true };
}

async function calendarSearchEvents(params) {
    var calendarId = encodeURIComponent(params.calendar_id || "primary");
    var maxResults = params.max_results || 20;
    var query = "?q=" + encodeURIComponent(params.query) + "&singleEvents=true&orderBy=startTime&maxResults=" + maxResults;
    var data = await calendarFetch("GET", "/calendars/" + calendarId + "/events" + query);
    return { events: data.items || [] };
}

async function calendarQuickAdd(params) {
    var calendarId = encodeURIComponent(params.calendar_id);
    return await calendarFetch("POST", "/calendars/" + calendarId + "/events/quickAdd?text=" + encodeURIComponent(params.text));
}

async function calendarGetFreeBusy(params) {
    var items = params.calendar_ids.map(function(id) { return { id: id }; });
    return await calendarFetch("POST", "/freeBusy", {
        timeMin: params.time_min,
        timeMax: params.time_max,
        items: items
    });
}

async function calendarCreateCalendar(params) {
    var body = { summary: params.summary };
    if (params.description) body.description = params.description;
    if (params.timezone) body.timeZone = params.timezone;
    return await calendarFetch("POST", "/calendars", body);
}

async function calendarDeleteCalendar(params) {
    var calendarId = encodeURIComponent(params.calendar_id);
    var token = await google.getAccessToken();
    var resp = await fetch(CALENDAR_API + "/calendars/" + calendarId, {
        method: "DELETE",
        headers: { "Authorization": "Bearer " + token }
    });
    if (!resp.ok && resp.status !== 204) {
        var errorText = await resp.text();
        throw new Error("Calendar API error (" + resp.status + "): " + errorText);
    }
    return { success: true };
}

async function calendarRespond(params) {
    var calendarId = encodeURIComponent(params.calendar_id);
    var event = await calendarFetch("GET", "/calendars/" + calendarId + "/events/" + params.event_id);
    var email = await google.getAccountEmail();
    var attendees = event.attendees || [];
    var found = false;
    for (var i = 0; i < attendees.length; i++) {
        if (attendees[i].email.toLowerCase() === email.toLowerCase()) {
            attendees[i].responseStatus = params.response;
            found = true;
            break;
        }
    }
    if (!found) {
        attendees.push({ email: email, responseStatus: params.response });
    }
    event.attendees = attendees;
    return await calendarFetch("PUT", "/calendars/" + calendarId + "/events/" + params.event_id, event);
}

async function calendarListColors(params) {
    return await calendarFetch("GET", "/colors");
}

async function calendarInstances(params) {
    var calendarId = encodeURIComponent(params.calendar_id);
    var query = "?";
    if (params.time_min) query += "timeMin=" + encodeURIComponent(params.time_min) + "&";
    if (params.time_max) query += "timeMax=" + encodeURIComponent(params.time_max) + "&";
    var maxResults = params.max_results || 20;
    query += "maxResults=" + maxResults;
    var data = await calendarFetch("GET", "/calendars/" + calendarId + "/events/" + params.event_id + "/instances" + query);
    return { instances: data.items || [], nextPageToken: data.nextPageToken };
}
