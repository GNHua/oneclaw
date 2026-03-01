/**
 * Google Gmail tool group for OneClawShadow.
 *
 * Uses:
 * - google.getAccessToken() -- from GoogleAuthBridge
 * - fetch() -- from FetchBridge (Web Fetch API style)
 * - console.log/error() -- from ConsoleBridge
 */

var GMAIL_API = "https://www.googleapis.com/gmail/v1/users/me";

async function gmailFetch(method, path, body) {
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
    var resp = await fetch(GMAIL_API + path, options);
    if (!resp.ok) {
        var errorText = await resp.text();
        throw new Error("Gmail API error (" + resp.status + "): " + errorText);
    }
    return await resp.json();
}

async function gmailSearch(params) {
    var query = params.query;
    var maxResults = params.max_results || 20;
    var path = "/messages?q=" + encodeURIComponent(query) + "&maxResults=" + maxResults;

    var data = await gmailFetch("GET", path);
    if (!data.messages || data.messages.length === 0) {
        return { messages: [], total: 0 };
    }

    var results = [];
    for (var i = 0; i < data.messages.length; i++) {
        var msg = await gmailFetch("GET", "/messages/" + data.messages[i].id +
            "?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date");
        var headers = msg.payload.headers;
        results.push({
            id: msg.id,
            threadId: msg.threadId,
            snippet: msg.snippet,
            subject: findHeader(headers, "Subject"),
            from: findHeader(headers, "From"),
            date: findHeader(headers, "Date"),
            labelIds: msg.labelIds || []
        });
    }
    return { messages: results, total: data.resultSizeEstimate || results.length };
}

async function gmailGetMessage(params) {
    var msg = await gmailFetch("GET", "/messages/" + params.message_id + "?format=full");
    var headers = msg.payload.headers;
    return {
        id: msg.id,
        threadId: msg.threadId,
        subject: findHeader(headers, "Subject"),
        from: findHeader(headers, "From"),
        to: findHeader(headers, "To"),
        cc: findHeader(headers, "Cc"),
        date: findHeader(headers, "Date"),
        body: extractBody(msg.payload),
        labelIds: msg.labelIds || [],
        attachments: extractAttachments(msg.payload)
    };
}

async function gmailSend(params) {
    var mime = buildMimeMessage(params);
    var encoded = btoa(unescape(encodeURIComponent(mime)))
        .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    var data = await gmailFetch("POST", "/messages/send", { raw: encoded });
    return { id: data.id, threadId: data.threadId };
}

async function gmailReply(params) {
    var original = await gmailFetch("GET", "/messages/" + params.message_id + "?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Message-ID&metadataHeaders=References");
    var headers = original.payload.headers;
    var subject = findHeader(headers, "Subject");
    if (!subject.startsWith("Re:")) subject = "Re: " + subject;
    var replyTo = findHeader(headers, "From");
    var messageId = findHeader(headers, "Message-ID");
    var references = findHeader(headers, "References");
    if (references) references = references + " " + messageId;
    else references = messageId;

    var replyParams = {
        to: replyTo,
        subject: subject,
        body: params.body,
        html: params.html || false
    };
    var mime = buildMimeMessage(replyParams);
    mime = "In-Reply-To: " + messageId + "\r\nReferences: " + references + "\r\n" + mime;
    var encoded = btoa(unescape(encodeURIComponent(mime)))
        .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    var data = await gmailFetch("POST", "/messages/send", {
        raw: encoded,
        threadId: original.threadId
    });
    return { id: data.id, threadId: data.threadId };
}

async function gmailTrash(params) {
    return await gmailFetch("POST", "/messages/" + params.message_id + "/trash");
}

async function gmailUntrash(params) {
    return await gmailFetch("POST", "/messages/" + params.message_id + "/untrash");
}

async function gmailMarkRead(params) {
    return await gmailFetch("POST", "/messages/" + params.message_id + "/modify", {
        removeLabelIds: ["UNREAD"]
    });
}

async function gmailMarkUnread(params) {
    return await gmailFetch("POST", "/messages/" + params.message_id + "/modify", {
        addLabelIds: ["UNREAD"]
    });
}

async function gmailAddLabel(params) {
    return await gmailFetch("POST", "/messages/" + params.message_id + "/modify", {
        addLabelIds: [params.label_id]
    });
}

async function gmailRemoveLabel(params) {
    return await gmailFetch("POST", "/messages/" + params.message_id + "/modify", {
        removeLabelIds: [params.label_id]
    });
}

async function gmailListLabels(params) {
    var data = await gmailFetch("GET", "/labels");
    return { labels: data.labels || [] };
}

async function gmailCreateLabel(params) {
    return await gmailFetch("POST", "/labels", { name: params.name });
}

async function gmailCreateDraft(params) {
    var mime = buildMimeMessage(params);
    var encoded = btoa(unescape(encodeURIComponent(mime)))
        .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    return await gmailFetch("POST", "/drafts", { message: { raw: encoded } });
}

async function gmailListDrafts(params) {
    var maxResults = params.max_results || 20;
    var data = await gmailFetch("GET", "/drafts?maxResults=" + maxResults);
    return { drafts: data.drafts || [] };
}

async function gmailSendDraft(params) {
    return await gmailFetch("POST", "/drafts/send", { id: params.draft_id });
}

async function gmailGetAttachment(params) {
    var token = await google.getAccessToken();
    var url = GMAIL_API + "/messages/" + params.message_id + "/attachments/" + params.attachment_id;
    var data = await gmailFetch("GET", "/messages/" + params.message_id + "/attachments/" + params.attachment_id);
    var decoded = atob(data.data.replace(/-/g, "+").replace(/_/g, "/"));
    var bytes = new Uint8Array(decoded.length);
    for (var i = 0; i < decoded.length; i++) {
        bytes[i] = decoded.charCodeAt(i);
    }
    return { size: data.size, saved: params.save_path };
}

async function gmailListThreads(params) {
    var query = params.query;
    var maxResults = params.max_results || 20;
    var path = "/threads?q=" + encodeURIComponent(query) + "&maxResults=" + maxResults;
    var data = await gmailFetch("GET", path);
    return { threads: data.threads || [], total: data.resultSizeEstimate || 0 };
}

async function gmailGetThread(params) {
    var data = await gmailFetch("GET", "/threads/" + params.thread_id + "?format=full");
    var messages = (data.messages || []).map(function(msg) {
        var headers = msg.payload.headers;
        return {
            id: msg.id,
            subject: findHeader(headers, "Subject"),
            from: findHeader(headers, "From"),
            to: findHeader(headers, "To"),
            date: findHeader(headers, "Date"),
            body: extractBody(msg.payload),
            labelIds: msg.labelIds || []
        };
    });
    return { id: data.id, messages: messages };
}

// --- Helpers ---

function findHeader(headers, name) {
    for (var i = 0; i < headers.length; i++) {
        if (headers[i].name.toLowerCase() === name.toLowerCase()) {
            return headers[i].value;
        }
    }
    return "";
}

function extractBody(payload) {
    if (payload.body && payload.body.data) {
        return decodeBase64Url(payload.body.data);
    }
    if (payload.parts) {
        for (var i = 0; i < payload.parts.length; i++) {
            var part = payload.parts[i];
            if (part.mimeType === "text/plain" && part.body && part.body.data) {
                return decodeBase64Url(part.body.data);
            }
        }
        for (var i = 0; i < payload.parts.length; i++) {
            var part = payload.parts[i];
            if (part.mimeType === "text/html" && part.body && part.body.data) {
                return decodeBase64Url(part.body.data);
            }
        }
    }
    return "";
}

function extractAttachments(payload) {
    var attachments = [];
    if (payload.parts) {
        for (var i = 0; i < payload.parts.length; i++) {
            var part = payload.parts[i];
            if (part.filename && part.filename.length > 0) {
                attachments.push({
                    filename: part.filename,
                    mimeType: part.mimeType,
                    size: part.body.size,
                    attachmentId: part.body.attachmentId
                });
            }
        }
    }
    return attachments;
}

function decodeBase64Url(data) {
    var str = data.replace(/-/g, "+").replace(/_/g, "/");
    return decodeURIComponent(escape(atob(str)));
}

function buildMimeMessage(params) {
    var lines = [];
    lines.push("To: " + params.to);
    if (params.cc) lines.push("Cc: " + params.cc);
    if (params.bcc) lines.push("Bcc: " + params.bcc);
    lines.push("Subject: " + params.subject);
    if (params.html) {
        lines.push("Content-Type: text/html; charset=UTF-8");
    } else {
        lines.push("Content-Type: text/plain; charset=UTF-8");
    }
    lines.push("");
    lines.push(params.body);
    return lines.join("\r\n");
}
