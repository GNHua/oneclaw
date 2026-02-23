var DOCS_API = "https://docs.googleapis.com/v1";

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

async function docsFetch(method, path, body) {
    var token = await getToken();
    var headers = { "Authorization": "Bearer " + token };
    var raw = await oneclaw.http.fetch(
        method,
        DOCS_API + path,
        body || null,
        "application/json",
        headers
    );
    var resp = JSON.parse(raw);
    if (resp.error) {
        throw new Error("Docs request failed: " + resp.error);
    }
    if (resp.status >= 400) {
        throw new Error("Docs API error (HTTP " + resp.status + "): " + resp.body);
    }
    if (!resp.body || resp.body.length === 0) return {};
    return JSON.parse(resp.body);
}

function extractText(doc) {
    var text = "";
    var content = doc.body && doc.body.content ? doc.body.content : [];
    for (var i = 0; i < content.length; i++) {
        var element = content[i];
        if (element.paragraph && element.paragraph.elements) {
            for (var j = 0; j < element.paragraph.elements.length; j++) {
                var el = element.paragraph.elements[j];
                if (el.textRun && el.textRun.content) {
                    text += el.textRun.content;
                }
            }
        }
    }
    return text;
}

async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "docs_get": {
                var doc = await docsFetch("GET",
                    "/documents/" + encodeURIComponent(args.document_id));
                return { output: JSON.stringify(doc, null, 2) };
            }

            case "docs_create": {
                var doc = await docsFetch("POST", "/documents",
                    JSON.stringify({ title: args.title }));
                return {
                    output: "Document created: " + doc.title +
                        "\nID: " + doc.documentId +
                        "\nURL: https://docs.google.com/document/d/" + doc.documentId + "/edit"
                };
            }

            case "docs_get_text": {
                var doc = await docsFetch("GET",
                    "/documents/" + encodeURIComponent(args.document_id));
                var text = extractText(doc);
                if (!text) {
                    return { output: "(document is empty)" };
                }
                return { output: text };
            }

            case "docs_insert": {
                var index = args.index !== undefined ? args.index : 1;
                var requests = [
                    {
                        insertText: {
                            location: { index: index },
                            text: args.text
                        }
                    }
                ];
                var result = await docsFetch("POST",
                    "/documents/" + encodeURIComponent(args.document_id) + ":batchUpdate",
                    JSON.stringify({ requests: requests }));
                return { output: "Text inserted at index " + index + " (" + args.text.length + " characters)." };
            }

            case "docs_delete_range": {
                var requests = [
                    {
                        deleteContentRange: {
                            range: {
                                startIndex: args.start_index,
                                endIndex: args.end_index
                            }
                        }
                    }
                ];
                var result = await docsFetch("POST",
                    "/documents/" + encodeURIComponent(args.document_id) + ":batchUpdate",
                    JSON.stringify({ requests: requests }));
                return { output: "Deleted content from index " + args.start_index + " to " + args.end_index + "." };
            }

            case "docs_find_replace": {
                var matchCase = args.match_case !== undefined ? args.match_case : false;
                var requests = [
                    {
                        replaceAllText: {
                            containsText: {
                                text: args.find,
                                matchCase: matchCase
                            },
                            replaceText: args.replace
                        }
                    }
                ];
                var result = await docsFetch("POST",
                    "/documents/" + encodeURIComponent(args.document_id) + ":batchUpdate",
                    JSON.stringify({ requests: requests }));
                var replies = result.replies || [];
                var count = 0;
                if (replies.length > 0 && replies[0].replaceAllText) {
                    count = replies[0].replaceAllText.occurrencesChanged || 0;
                }
                return { output: "Replaced " + count + " occurrence(s) of '" + args.find + "' with '" + args.replace + "'." };
            }

            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        var msg = (e && e.message) ? e.message : String(e || "Unknown error");
        oneclaw.log.error("docs error: " + msg);
        return { error: msg };
    }
}
