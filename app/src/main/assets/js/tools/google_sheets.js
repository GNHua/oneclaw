/**
 * Google Sheets tool group for OneClawShadow.
 */

var SHEETS_API = "https://sheets.googleapis.com/v4";

async function sheetsFetch(method, path, body) {
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
    var resp = await fetch(SHEETS_API + path, options);
    if (!resp.ok) {
        var errorText = await resp.text();
        throw new Error("Sheets API error (" + resp.status + "): " + errorText);
    }
    return await resp.json();
}

async function sheetsGet(params) {
    var data = await sheetsFetch("GET", "/spreadsheets/" + params.spreadsheet_id + "?fields=spreadsheetId,properties,sheets.properties");
    return {
        spreadsheetId: data.spreadsheetId,
        title: data.properties && data.properties.title,
        sheets: (data.sheets || []).map(function(s) {
            return {
                sheetId: s.properties && s.properties.sheetId,
                title: s.properties && s.properties.title,
                index: s.properties && s.properties.index,
                rowCount: s.properties && s.properties.gridProperties && s.properties.gridProperties.rowCount,
                columnCount: s.properties && s.properties.gridProperties && s.properties.gridProperties.columnCount
            };
        })
    };
}

async function sheetsReadValues(params) {
    var range = encodeURIComponent(params.range);
    var data = await sheetsFetch("GET", "/spreadsheets/" + params.spreadsheet_id + "/values/" + range);
    return {
        range: data.range,
        values: data.values || []
    };
}

async function sheetsWriteValues(params) {
    var range = encodeURIComponent(params.range);
    var inputOption = params.value_input_option || "USER_ENTERED";
    return await sheetsFetch("PUT",
        "/spreadsheets/" + params.spreadsheet_id + "/values/" + range + "?valueInputOption=" + inputOption,
        { range: params.range, values: params.values }
    );
}

async function sheetsAppendValues(params) {
    var range = encodeURIComponent(params.range);
    return await sheetsFetch("POST",
        "/spreadsheets/" + params.spreadsheet_id + "/values/" + range + ":append?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS",
        { values: params.values }
    );
}

async function sheetsClearValues(params) {
    var range = encodeURIComponent(params.range);
    return await sheetsFetch("POST",
        "/spreadsheets/" + params.spreadsheet_id + "/values/" + range + ":clear",
        {}
    );
}

async function sheetsAddSheet(params) {
    return await sheetsFetch("POST", "/spreadsheets/" + params.spreadsheet_id + ":batchUpdate", {
        requests: [{
            addSheet: {
                properties: { title: params.title }
            }
        }]
    });
}

async function sheetsBatchUpdate(params) {
    return await sheetsFetch("POST", "/spreadsheets/" + params.spreadsheet_id + ":batchUpdate", {
        requests: params.requests
    });
}
