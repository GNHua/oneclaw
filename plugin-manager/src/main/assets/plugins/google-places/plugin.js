var API_BASE = "https://places.googleapis.com/v1";

var SEARCH_FIELD_MASK = [
    "places.id",
    "places.displayName",
    "places.formattedAddress",
    "places.location",
    "places.rating",
    "places.priceLevel",
    "places.types",
    "places.currentOpeningHours",
    "nextPageToken"
].join(",");

var DETAILS_FIELD_MASK = [
    "id",
    "displayName",
    "formattedAddress",
    "location",
    "rating",
    "priceLevel",
    "types",
    "regularOpeningHours",
    "currentOpeningHours",
    "nationalPhoneNumber",
    "websiteUri"
].join(",");

var RESOLVE_FIELD_MASK = [
    "places.id",
    "places.displayName",
    "places.formattedAddress",
    "places.location",
    "places.types"
].join(",");

var PRICE_LEVEL_TO_ENUM = {
    0: "PRICE_LEVEL_FREE",
    1: "PRICE_LEVEL_INEXPENSIVE",
    2: "PRICE_LEVEL_MODERATE",
    3: "PRICE_LEVEL_EXPENSIVE",
    4: "PRICE_LEVEL_VERY_EXPENSIVE"
};

var ENUM_TO_PRICE_LEVEL = {};
for (var k in PRICE_LEVEL_TO_ENUM) {
    ENUM_TO_PRICE_LEVEL[PRICE_LEVEL_TO_ENUM[k]] = parseInt(k);
}

var PRICE_LABEL = ["Free", "Inexpensive", "Moderate", "Expensive", "Very Expensive"];

async function getApiKey() {
    var key = await oneclaw.credentials.getProviderKey("GoogleMaps");
    if (!key) {
        throw new Error("Google Maps API key not configured. Please set the GoogleMaps API key in Settings > API Keys.");
    }
    return key;
}

async function placesRequest(method, path, body, fieldMask) {
    var apiKey = await getApiKey();
    var url = API_BASE + path;
    var headers = {
        "X-Goog-Api-Key": apiKey,
        "X-Goog-FieldMask": fieldMask
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
        var detail = "";
        try { detail = JSON.parse(resp.body).error.message || resp.body; } catch (e) { detail = resp.body; }
        throw new Error("Google Places API error (HTTP " + resp.status + "): " + detail);
    }

    if (!resp.body || resp.body === "") {
        return {};
    }
    return JSON.parse(resp.body);
}

function parseDisplayName(raw) {
    if (!raw) return null;
    return raw.text || null;
}

function parseLatLng(raw) {
    if (!raw) return null;
    if (raw.latitude == null || raw.longitude == null) return null;
    return { lat: raw.latitude, lng: raw.longitude };
}

function parseOpenNow(raw) {
    if (!raw) return null;
    if (raw.openNow == null) return null;
    return raw.openNow;
}

function parseHours(raw) {
    if (!raw) return null;
    return raw.weekdayDescriptions || null;
}

function parsePriceLevel(raw) {
    if (!raw) return null;
    if (ENUM_TO_PRICE_LEVEL[raw] != null) return ENUM_TO_PRICE_LEVEL[raw];
    return null;
}

// --- Search ---

function buildSearchBody(args) {
    var query = args.query;
    if (args.keyword) {
        query = query + " " + args.keyword;
    }

    var body = {
        textQuery: query,
        pageSize: Math.min(Math.max(args.max_results || 10, 1), 20)
    };

    if (args.page_token) {
        body.pageToken = args.page_token;
    }

    if (args.latitude != null && args.longitude != null) {
        body.locationBias = {
            circle: {
                center: {
                    latitude: args.latitude,
                    longitude: args.longitude
                },
                radius: Math.min(Math.max(args.radius_meters || 5000, 100), 50000)
            }
        };
    }

    if (args.type) {
        body.includedType = args.type;
    }
    if (args.open_now != null) {
        body.openNow = args.open_now;
    }
    if (args.min_rating != null) {
        body.minRating = args.min_rating;
    }
    if (args.price_levels && args.price_levels.length > 0) {
        body.priceLevels = [];
        for (var i = 0; i < args.price_levels.length; i++) {
            var lvl = args.price_levels[i];
            if (PRICE_LEVEL_TO_ENUM[lvl]) {
                body.priceLevels.push(PRICE_LEVEL_TO_ENUM[lvl]);
            }
        }
    }

    return body;
}

function formatPlaceSummary(place, index) {
    var name = parseDisplayName(place.displayName) || "Unknown";
    var address = place.formattedAddress || "";
    var location = parseLatLng(place.location);
    var rating = place.rating;
    var priceLevel = parsePriceLevel(place.priceLevel);
    var openNow = parseOpenNow(place.currentOpeningHours);
    var types = place.types || [];

    var lines = [];
    lines.push(index + ". " + name);
    if (address) lines.push("   Address: " + address);
    if (location) lines.push("   Location: " + location.lat.toFixed(6) + ", " + location.lng.toFixed(6));
    if (rating != null) lines.push("   Rating: " + rating + "/5");
    if (priceLevel != null) lines.push("   Price: " + PRICE_LABEL[priceLevel] + " (" + priceLevel + "/4)");
    if (openNow != null) lines.push("   Open now: " + (openNow ? "Yes" : "No"));
    if (types.length > 0) lines.push("   Types: " + types.join(", "));
    lines.push("   Place ID: " + (place.id || ""));

    return lines.join("\n");
}

async function placesSearch(args) {
    if (!args.query) {
        return { error: "Missing required field: query" };
    }

    var body = buildSearchBody(args);
    var data = await placesRequest("POST", "/places:searchText", body, SEARCH_FIELD_MASK);
    var places = data.places || [];

    if (places.length === 0) {
        return { output: "No places found for \"" + args.query + "\"." };
    }

    var lines = [];
    lines.push("Found " + places.length + " result(s) for \"" + args.query + "\":\n");
    for (var i = 0; i < places.length; i++) {
        lines.push(formatPlaceSummary(places[i], i + 1));
    }

    if (data.nextPageToken) {
        lines.push("\nMore results available. Use page_token: \"" + data.nextPageToken + "\" to get the next page.");
    }

    return { output: lines.join("\n") };
}

// --- Details ---

function formatPlaceDetails(place) {
    var name = parseDisplayName(place.displayName) || "Unknown";
    var address = place.formattedAddress || "";
    var location = parseLatLng(place.location);
    var rating = place.rating;
    var priceLevel = parsePriceLevel(place.priceLevel);
    var phone = place.nationalPhoneNumber;
    var website = place.websiteUri;
    var hours = parseHours(place.regularOpeningHours);
    var openNow = parseOpenNow(place.currentOpeningHours);
    var types = place.types || [];

    var lines = [];
    lines.push(name);
    if (address) lines.push("Address: " + address);
    if (location) lines.push("Location: " + location.lat.toFixed(6) + ", " + location.lng.toFixed(6));
    if (rating != null) lines.push("Rating: " + rating + "/5");
    if (priceLevel != null) lines.push("Price: " + PRICE_LABEL[priceLevel] + " (" + priceLevel + "/4)");
    if (openNow != null) lines.push("Open now: " + (openNow ? "Yes" : "No"));
    if (phone) lines.push("Phone: " + phone);
    if (website) lines.push("Website: " + website);
    if (types.length > 0) lines.push("Types: " + types.join(", "));
    lines.push("Place ID: " + (place.id || ""));

    if (hours && hours.length > 0) {
        lines.push("\nOpening hours:");
        for (var i = 0; i < hours.length; i++) {
            lines.push("  " + hours[i]);
        }
    }

    return lines.join("\n");
}

async function placesDetails(args) {
    if (!args.place_id) {
        return { error: "Missing required field: place_id" };
    }

    var data = await placesRequest("GET", "/places/" + args.place_id, null, DETAILS_FIELD_MASK);
    return { output: formatPlaceDetails(data) };
}

// --- Resolve ---

async function placesResolve(args) {
    if (!args.location_text) {
        return { error: "Missing required field: location_text" };
    }

    var limit = Math.min(Math.max(args.limit || 5, 1), 10);
    var body = { textQuery: args.location_text, pageSize: limit };
    var data = await placesRequest("POST", "/places:searchText", body, RESOLVE_FIELD_MASK);
    var places = data.places || [];

    if (places.length === 0) {
        return { output: "No locations found for \"" + args.location_text + "\"." };
    }

    var lines = [];
    lines.push("Resolved " + places.length + " location(s) for \"" + args.location_text + "\":\n");
    for (var i = 0; i < places.length; i++) {
        var p = places[i];
        var name = parseDisplayName(p.displayName) || "Unknown";
        var address = p.formattedAddress || "";
        var location = parseLatLng(p.location);
        var types = p.types || [];

        lines.push((i + 1) + ". " + name);
        if (address) lines.push("   Address: " + address);
        if (location) lines.push("   Location: " + location.lat.toFixed(6) + ", " + location.lng.toFixed(6));
        if (types.length > 0) lines.push("   Types: " + types.join(", "));
        lines.push("   Place ID: " + (p.id || ""));
    }

    return { output: lines.join("\n") };
}

// --- Entry point ---

async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "places_search":
                return await placesSearch(args);
            case "places_details":
                return await placesDetails(args);
            case "places_resolve":
                return await placesResolve(args);
            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        oneclaw.log.error("google-places error: " + e.message);
        return { error: e.message };
    }
}
