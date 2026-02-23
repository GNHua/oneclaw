var GMAIL_API = "https://www.googleapis.com/gmail/v1/users/me";

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

async function gmailFetch(method, path, body) {
    var token = await getToken();
    var headers = { "Authorization": "Bearer " + token };
    var raw = await oneclaw.http.fetch(
        method,
        GMAIL_API + path,
        body || null,
        "application/json",
        headers
    );
    var resp = JSON.parse(raw);
    if (resp.error) {
        throw new Error("Gmail request failed: " + resp.error);
    }
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

function extractAttachments(payload) {
    var attachments = [];
    if (!payload) return attachments;
    if (payload.filename && payload.filename.length > 0 && payload.body) {
        attachments.push({
            filename: payload.filename,
            mimeType: payload.mimeType || "",
            size: payload.body.size || 0,
            attachmentId: payload.body.attachmentId || ""
        });
    }
    if (payload.parts) {
        for (var i = 0; i < payload.parts.length; i++) {
            attachments = attachments.concat(extractAttachments(payload.parts[i]));
        }
    }
    return attachments;
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
                var attachments = extractAttachments(msg.payload);

                var result = {
                    id: msg.id,
                    threadId: msg.threadId,
                    historyId: msg.historyId,
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

            case "gmail_get_thread": {
                var data = await gmailFetch("GET",
                    "/threads/" + args.thread_id + "?format=metadata" +
                    "&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date&metadataHeaders=To");
                var messages = (data.messages || []).map(function(msg) {
                    var hdrs = msg.payload ? msg.payload.headers : [];
                    return {
                        id: msg.id,
                        subject: getHeader(hdrs, "Subject"),
                        from: getHeader(hdrs, "From"),
                        to: getHeader(hdrs, "To"),
                        date: getHeader(hdrs, "Date"),
                        snippet: msg.snippet,
                        labels: msg.labelIds || []
                    };
                });
                return { output: JSON.stringify({ threadId: data.id, messages: messages }, null, 2) };
            }

            case "gmail_modify_labels": {
                var targetType = args.type || "message";
                var path = targetType === "thread"
                    ? "/threads/" + args.id + "/modify"
                    : "/messages/" + args.id + "/modify";
                var body = {};
                if (args.add_labels) body.addLabelIds = args.add_labels;
                if (args.remove_labels) body.removeLabelIds = args.remove_labels;
                await gmailFetch("POST", path, JSON.stringify(body));
                return { output: "Labels modified on " + targetType + " " + args.id };
            }

            case "gmail_create_label": {
                var labelBody = { name: args.name };
                if (args.label_list_visibility) labelBody.labelListVisibility = args.label_list_visibility;
                if (args.message_list_visibility) labelBody.messageListVisibility = args.message_list_visibility;
                var data = await gmailFetch("POST", "/labels", JSON.stringify(labelBody));
                return { output: "Label created: " + data.name + " (ID: " + data.id + ")" };
            }

            case "gmail_delete_label": {
                await gmailFetch("DELETE", "/labels/" + args.label_id);
                return { output: "Label deleted: " + args.label_id };
            }

            case "gmail_list_drafts": {
                var maxResults = args.max_results || 10;
                if (maxResults > 50) maxResults = 50;
                var data = await gmailFetch("GET", "/drafts?maxResults=" + maxResults);
                if (!data.drafts || data.drafts.length === 0) {
                    return { output: "No drafts found." };
                }
                var drafts = [];
                for (var i = 0; i < data.drafts.length; i++) {
                    var d = await gmailFetch("GET",
                        "/drafts/" + data.drafts[i].id + "?format=metadata" +
                        "&metadataHeaders=Subject&metadataHeaders=To&metadataHeaders=Date");
                    var hdrs = d.message && d.message.payload ? d.message.payload.headers : [];
                    drafts.push({
                        id: d.id,
                        messageId: d.message ? d.message.id : "",
                        subject: getHeader(hdrs, "Subject"),
                        to: getHeader(hdrs, "To"),
                        date: getHeader(hdrs, "Date"),
                        snippet: d.message ? d.message.snippet : ""
                    });
                }
                return { output: JSON.stringify(drafts, null, 2) };
            }

            case "gmail_get_draft": {
                var data = await gmailFetch("GET", "/drafts/" + args.draft_id + "?format=full");
                var msg = data.message || {};
                var hdrs = msg.payload ? msg.payload.headers : [];
                var textBody = getTextBody(msg.payload) || "(no plain text body)";
                var result = {
                    id: data.id,
                    messageId: msg.id,
                    subject: getHeader(hdrs, "Subject"),
                    to: getHeader(hdrs, "To"),
                    cc: getHeader(hdrs, "Cc"),
                    date: getHeader(hdrs, "Date"),
                    body: textBody
                };
                return { output: JSON.stringify(result, null, 2) };
            }

            case "gmail_create_draft": {
                var contentType = args.content_type || "text/plain";
                var mime = "To: " + args.to + "\r\n" +
                    "Subject: " + args.subject + "\r\n" +
                    "Content-Type: " + contentType + "; charset=utf-8\r\n";
                if (args.cc) mime += "Cc: " + args.cc + "\r\n";
                mime += "\r\n" + args.body;
                var encoded = encodeBase64Url(mime);
                var data = await gmailFetch("POST", "/drafts",
                    JSON.stringify({ message: { raw: encoded } }));
                return { output: "Draft created. Draft ID: " + data.id };
            }

            case "gmail_send_draft": {
                var data = await gmailFetch("POST", "/drafts/send",
                    JSON.stringify({ id: args.draft_id }));
                return { output: "Draft sent. Message ID: " + data.id };
            }

            case "gmail_delete_draft": {
                await gmailFetch("DELETE", "/drafts/" + args.draft_id);
                return { output: "Draft deleted: " + args.draft_id };
            }

            case "gmail_get_attachment": {
                var data = await gmailFetch("GET",
                    "/messages/" + args.message_id + "/attachments/" + args.attachment_id);
                var base64Data = data.data || "";
                // Convert URL-safe base64 to standard base64
                var standard = base64Data.replace(/-/g, "+").replace(/_/g, "/");
                while (standard.length % 4 !== 0) standard += "=";
                var decoded = atob(standard);
                var filename = args.filename || "attachment";
                var savePath = "downloads/" + filename;
                // Write binary content as raw bytes
                oneclaw.fs.writeFile(savePath, decoded);
                return { output: "Attachment saved to workspace: " + savePath + " (" + decoded.length + " bytes)" };
            }

            case "gmail_history": {
                var maxResults = args.max_results || 20;
                var data = await gmailFetch("GET",
                    "/history?startHistoryId=" + args.start_history_id +
                    "&maxResults=" + maxResults);
                var history = (data.history || []).map(function(h) {
                    var entry = { id: h.id };
                    if (h.messagesAdded) {
                        entry.messagesAdded = h.messagesAdded.map(function(m) {
                            return { id: m.message.id, threadId: m.message.threadId, labels: m.message.labelIds || [] };
                        });
                    }
                    if (h.labelsAdded) {
                        entry.labelsAdded = h.labelsAdded.map(function(m) {
                            return { messageId: m.message.id, labels: m.labelIds || [] };
                        });
                    }
                    if (h.labelsRemoved) {
                        entry.labelsRemoved = h.labelsRemoved.map(function(m) {
                            return { messageId: m.message.id, labels: m.labelIds || [] };
                        });
                    }
                    return entry;
                });
                return { output: JSON.stringify({ historyId: data.historyId, history: history }, null, 2) };
            }

            case "gmail_batch_modify": {
                var body = { ids: args.message_ids };
                if (args.add_labels) body.addLabelIds = args.add_labels;
                if (args.remove_labels) body.removeLabelIds = args.remove_labels;
                await gmailFetch("POST", "/messages/batchModify", JSON.stringify(body));
                return { output: "Batch modified " + args.message_ids.length + " message(s)." };
            }

            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        var msg = (e && e.message) ? e.message : String(e || "Unknown error");
        oneclaw.log.error("gmail error: " + msg);
        return { error: msg };
    }
}
