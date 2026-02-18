async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "ha_list_entities":
                return await haListEntities(args);
            case "ha_get_state":
                return await haGetState(args);
            case "ha_call_service":
                return await haCallService(args);
            case "ha_get_history":
                return await haGetHistory(args);
            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        oneclaw.log.error("smart-home error: " + e.message);
        return { error: e.message };
    }
}

async function getConfig() {
    var baseUrl = await oneclaw.credentials.get("base_url");
    var apiKey = await oneclaw.credentials.get("api_key");
    if (!baseUrl || !apiKey) {
        throw new Error("Home Assistant not configured. Please set the URL and Access Token in Settings > Plugins > Smart Home.");
    }
    // Remove trailing slash
    baseUrl = baseUrl.replace(/\/+$/, "");
    return { baseUrl: baseUrl, apiKey: apiKey };
}

async function haFetch(method, path, body) {
    var config = await getConfig();
    var url = config.baseUrl + path;
    var headers = {
        "Authorization": "Bearer " + config.apiKey
    };

    var raw = await oneclaw.http.fetch(
        method,
        url,
        body ? JSON.stringify(body) : "",
        "application/json",
        headers
    );

    var resp = JSON.parse(raw);
    if (resp.status >= 400) {
        var errorMsg = resp.body || "HTTP " + resp.status;
        throw new Error("Home Assistant API error (HTTP " + resp.status + "): " + errorMsg);
    }

    return JSON.parse(resp.body);
}

async function haListEntities(args) {
    var states = await haFetch("GET", "/api/states");
    var domain = args.domain || "";
    var maxResults = args.max_results || 50;

    if (domain) {
        states = states.filter(function(s) {
            return s.entity_id.startsWith(domain + ".");
        });
    }

    states.sort(function(a, b) {
        return a.entity_id.localeCompare(b.entity_id);
    });

    if (states.length > maxResults) {
        states = states.slice(0, maxResults);
    }

    if (states.length === 0) {
        return { output: "No entities found" + (domain ? " for domain '" + domain + "'" : "") + "." };
    }

    var lines = [];
    lines.push("Found " + states.length + " entities" + (domain ? " in domain '" + domain + "'" : "") + ":\n");
    for (var i = 0; i < states.length; i++) {
        var s = states[i];
        var friendly = (s.attributes && s.attributes.friendly_name) || "";
        var line = "- " + s.entity_id + ": " + s.state;
        if (friendly) {
            line += " (" + friendly + ")";
        }
        lines.push(line);
    }

    return { output: lines.join("\n") };
}

async function haGetState(args) {
    if (!args.entity_id) {
        return { error: "Missing required field: entity_id" };
    }

    var state = await haFetch("GET", "/api/states/" + args.entity_id);

    var lines = [];
    lines.push("Entity: " + state.entity_id);
    lines.push("State: " + state.state);
    if (state.last_changed) {
        lines.push("Last changed: " + state.last_changed);
    }
    if (state.last_updated) {
        lines.push("Last updated: " + state.last_updated);
    }

    if (state.attributes && Object.keys(state.attributes).length > 0) {
        lines.push("\nAttributes:");
        var keys = Object.keys(state.attributes).sort();
        for (var i = 0; i < keys.length; i++) {
            var val = state.attributes[keys[i]];
            if (typeof val === "object" && val !== null) {
                val = JSON.stringify(val);
            }
            lines.push("  " + keys[i] + ": " + val);
        }
    }

    return { output: lines.join("\n") };
}

async function haCallService(args) {
    if (!args.domain) {
        return { error: "Missing required field: domain" };
    }
    if (!args.service) {
        return { error: "Missing required field: service" };
    }
    if (!args.entity_id) {
        return { error: "Missing required field: entity_id" };
    }

    var body = { entity_id: args.entity_id };
    if (args.service_data && typeof args.service_data === "object") {
        var keys = Object.keys(args.service_data);
        for (var i = 0; i < keys.length; i++) {
            body[keys[i]] = args.service_data[keys[i]];
        }
    }

    var path = "/api/services/" + args.domain + "/" + args.service;
    await haFetch("POST", path, body);

    // Get the updated state
    var newState = await haFetch("GET", "/api/states/" + args.entity_id);

    var output = "Service " + args.domain + "." + args.service + " called on " + args.entity_id + ".\n";
    output += "Current state: " + newState.state;
    if (newState.attributes && newState.attributes.friendly_name) {
        output += " (" + newState.attributes.friendly_name + ")";
    }

    return { output: output };
}

async function haGetHistory(args) {
    if (!args.entity_id) {
        return { error: "Missing required field: entity_id" };
    }

    var hours = Math.min(args.hours || 24, 168);
    var now = new Date();
    var start = new Date(now.getTime() - hours * 60 * 60 * 1000);
    var timestamp = start.toISOString();

    var path = "/api/history/period/" + timestamp + "?filter_entity_id=" + args.entity_id + "&minimal_response&no_attributes";
    var history = await haFetch("GET", path);

    if (!history || history.length === 0 || history[0].length === 0) {
        return { output: "No history found for " + args.entity_id + " in the last " + hours + " hours." };
    }

    var entries = history[0];
    var lines = [];
    lines.push("History for " + args.entity_id + " (last " + hours + " hours):");
    lines.push("Total state changes: " + entries.length + "\n");

    // Show up to 50 entries
    var maxEntries = Math.min(entries.length, 50);
    for (var i = 0; i < maxEntries; i++) {
        var e = entries[i];
        var time = e.last_changed || e.last_updated || "";
        if (time) {
            var d = new Date(time);
            time = d.toLocaleString();
        }
        lines.push("  " + time + " -> " + e.state);
    }

    if (entries.length > maxEntries) {
        lines.push("  ... and " + (entries.length - maxEntries) + " more entries");
    }

    return { output: lines.join("\n") };
}
