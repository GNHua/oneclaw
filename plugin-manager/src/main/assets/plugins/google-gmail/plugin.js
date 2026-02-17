var GMAIL_API = "https://www.googleapis.com/gmail/v1/users/me";

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

async function gmailFetch(method, path, body) {
    var token = await getToken();
    var headers = { "Authorization": "Bearer " + token };
    var raw = await palmclaw.http.fetch(
        method,
        GMAIL_API + path,
        body || null,
        "application/json",
        headers
    );
    var resp = JSON.parse(raw);
    if (resp.status >= 400) {
        throw new Error("Gmail API error (HTTP " + resp.status + "): " + resp.body);
    }
    if (!resp.body || resp.body.length === 0) return {};
    return JSON.parse(resp.body);
}

function decodeBase64Url(str) {
    var base64 = str.replace(/-/g, "+").replace(/_/g, "/");
    while (base64.length % 4 !== 0) base64 += "=";
    try {
        return atob(base64);
    } catch (e) {
        return "(failed to decode body)";
    }
}

function encodeBase64Url(str) {
    return btoa(str)
        .replace(/\+/g, "-")
        .replace(/\//g, "_")
        .replace(/=+$/, "");
}

function getHeader(headers, name) {
    if (!headers) return "";
    var lower = name.toLowerCase();
    for (var i = 0; i < headers.length; i++) {
        if (headers[i].name.toLowerCase() === lower) {
            return headers[i].value;
        }
    }
    return "";
}

function getTextBody(payload) {
    if (!payload) return null;
    if (payload.mimeType === "text/plain" && payload.body && payload.body.data) {
        return decodeBase64Url(payload.body.data);
    }
    if (payload.parts) {
        for (var i = 0; i < payload.parts.length; i++) {
            var result = getTextBody(payload.parts[i]);
            if (result) return result;
        }
    }
    return null;
}

async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "gmail_search": {
                var maxResults = args.max_results || 10;
                if (maxResults > 50) maxResults = 50;
                var data = await gmailFetch("GET",
                    "/messages?q=" + encodeURIComponent(args.query) +
                    "&maxResults=" + maxResults);

                if (!data.messages || data.messages.length === 0) {
                    return { output: "No messages found matching: " + args.query };
                }

                var results = [];
                for (var i = 0; i < data.messages.length; i++) {
                    var msg = await gmailFetch("GET",
                        "/messages/" + data.messages[i].id + "?format=metadata" +
                        "&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date");

                    var headers = msg.payload ? msg.payload.headers : [];
                    results.push({
                        id: msg.id,
                        threadId: msg.threadId,
                        subject: getHeader(headers, "Subject"),
                        from: getHeader(headers, "From"),
                        date: getHeader(headers, "Date"),
                        snippet: msg.snippet
                    });
                }

                return { output: JSON.stringify(results, null, 2) };
            }

            case "gmail_get_message": {
                var msg = await gmailFetch("GET",
                    "/messages/" + args.message_id + "?format=full");

                var headers = msg.payload ? msg.payload.headers : [];
                var textBody = getTextBody(msg.payload) || "(no plain text body)";

                var attachments = [];
                if (msg.payload && msg.payload.parts) {
                    for (var i = 0; i < msg.payload.parts.length; i++) {
                        var part = msg.payload.parts[i];
                        if (part.filename && part.filename.length > 0) {
                            attachments.push(part.filename);
                        }
                    }
                }

                var result = {
                    id: msg.id,
                    threadId: msg.threadId,
                    subject: getHeader(headers, "Subject"),
                    from: getHeader(headers, "From"),
                    to: getHeader(headers, "To"),
                    cc: getHeader(headers, "Cc"),
                    date: getHeader(headers, "Date"),
                    body: textBody,
                    attachments: attachments,
                    labels: msg.labelIds || []
                };

                return { output: JSON.stringify(result, null, 2) };
            }

            case "gmail_send": {
                var contentType = args.content_type || "text/plain";
                var mime = "To: " + args.to + "\r\n" +
                    "Subject: " + args.subject + "\r\n" +
                    "Content-Type: " + contentType + "; charset=utf-8\r\n";
                if (args.cc) {
                    mime += "Cc: " + args.cc + "\r\n";
                }
                mime += "\r\n" + args.body;

                var encoded = encodeBase64Url(mime);

                var data = await gmailFetch("POST", "/messages/send",
                    JSON.stringify({ raw: encoded }));

                return { output: "Email sent successfully. Message ID: " + data.id };
            }

            case "gmail_reply": {
                var original = await gmailFetch("GET",
                    "/messages/" + args.message_id + "?format=metadata" +
                    "&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Message-Id");

                var origHeaders = original.payload ? original.payload.headers : [];
                var origSubject = getHeader(origHeaders, "Subject");
                var origFrom = getHeader(origHeaders, "From");
                var origMsgId = getHeader(origHeaders, "Message-Id");

                var subject = origSubject.indexOf("Re: ") === 0 ? origSubject : "Re: " + origSubject;
                var mime = "To: " + origFrom + "\r\n" +
                    "Subject: " + subject + "\r\n" +
                    "In-Reply-To: " + origMsgId + "\r\n" +
                    "References: " + origMsgId + "\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n\r\n" +
                    args.body;

                var encoded = encodeBase64Url(mime);

                var data = await gmailFetch("POST", "/messages/send",
                    JSON.stringify({ raw: encoded, threadId: original.threadId }));

                return { output: "Reply sent. Message ID: " + data.id };
            }

            case "gmail_delete": {
                var ids = args.message_ids;
                if (!ids || ids.length === 0) {
                    return { error: "No message IDs provided" };
                }
                if (ids.length === 1) {
                    await gmailFetch("POST", "/messages/" + ids[0] + "/trash");
                } else {
                    await gmailFetch("POST", "/messages/batchModify",
                        JSON.stringify({ ids: ids, addLabelIds: ["TRASH"] }));
                }
                return { output: "Moved " + ids.length + " message(s) to Trash." };
            }

            case "gmail_list_labels": {
                var data = await gmailFetch("GET", "/labels");
                var labels = (data.labels || []).map(function(l) {
                    return { id: l.id, name: l.name, type: l.type };
                });
                return { output: JSON.stringify(labels, null, 2) };
            }

            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        palmclaw.log.error("gmail error: " + e.message);
        return { error: e.message };
    }
}
