var SHEETS_API = "https://sheets.googleapis.com/v4";

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

async function sheetsFetch(method, path, body) {
    var token = await getToken();
    var headers = { "Authorization": "Bearer " + token };
    var raw = await oneclaw.http.fetch(
        method,
        SHEETS_API + path,
        body || null,
        "application/json",
        headers
    );
    var resp = JSON.parse(raw);
    if (resp.status >= 400) {
        throw new Error("Sheets API error (HTTP " + resp.status + "): " + resp.body);
    }
    if (!resp.body || resp.body.length === 0) return {};
    return JSON.parse(resp.body);
}

async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "sheets_get_values": {
                var spreadsheetId = encodeURIComponent(args.spreadsheet_id);
                var range = encodeURIComponent(args.range);

                var data = await sheetsFetch("GET",
                    "/spreadsheets/" + spreadsheetId + "/values/" + range);

                if (!data.values || data.values.length === 0) {
                    return { output: "No data found in range " + args.range };
                }

                return { output: JSON.stringify(data, null, 2) };
            }

            case "sheets_update_values": {
                var spreadsheetId = encodeURIComponent(args.spreadsheet_id);
                var range = encodeURIComponent(args.range);
                var values = JSON.parse(args.values);

                var body = JSON.stringify({ values: values });

                var data = await sheetsFetch("PUT",
                    "/spreadsheets/" + spreadsheetId + "/values/" + range +
                    "?valueInputOption=USER_ENTERED",
                    body);

                return {
                    output: "Updated " + (data.updatedCells || 0) + " cells in range " +
                        (data.updatedRange || args.range)
                };
            }

            case "sheets_append": {
                var spreadsheetId = encodeURIComponent(args.spreadsheet_id);
                var range = encodeURIComponent(args.range);
                var values = JSON.parse(args.values);

                var body = JSON.stringify({ values: values });

                var data = await sheetsFetch("POST",
                    "/spreadsheets/" + spreadsheetId + "/values/" + range +
                    ":append?valueInputOption=USER_ENTERED",
                    body);

                var updates = data.updates || {};
                return {
                    output: "Appended " + (updates.updatedRows || values.length) +
                        " rows to " + (updates.updatedRange || args.range)
                };
            }

            case "sheets_clear": {
                var spreadsheetId = encodeURIComponent(args.spreadsheet_id);
                var range = encodeURIComponent(args.range);

                var data = await sheetsFetch("POST",
                    "/spreadsheets/" + spreadsheetId + "/values/" + range + ":clear",
                    JSON.stringify({}));

                return {
                    output: "Cleared range " + (data.clearedRange || args.range)
                };
            }

            case "sheets_metadata": {
                var spreadsheetId = encodeURIComponent(args.spreadsheet_id);

                var data = await sheetsFetch("GET",
                    "/spreadsheets/" + spreadsheetId +
                    "?fields=spreadsheetId,properties.title,sheets.properties");

                return { output: JSON.stringify(data, null, 2) };
            }

            case "sheets_create": {
                var body = JSON.stringify({
                    properties: { title: args.title }
                });

                var data = await sheetsFetch("POST", "/spreadsheets", body);

                return {
                    output: "Spreadsheet created: " + data.properties.title +
                        "\nID: " + data.spreadsheetId +
                        "\nURL: " + data.spreadsheetUrl
                };
            }

            case "sheets_batch_update": {
                var spreadsheetId = encodeURIComponent(args.spreadsheet_id);
                var requests = JSON.parse(args.requests);

                var body = JSON.stringify({ requests: requests });

                var data = await sheetsFetch("POST",
                    "/spreadsheets/" + spreadsheetId + ":batchUpdate",
                    body);

                return { output: JSON.stringify(data, null, 2) };
            }

            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        oneclaw.log.error("sheets error: " + e.message);
        return { error: e.message };
    }
}
