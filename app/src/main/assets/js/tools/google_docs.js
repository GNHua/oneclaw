/**
 * Google Docs tool group for OneClawShadow.
 */

var DOCS_API = "https://docs.googleapis.com/v1";

async function docsFetch(method, path, body) {
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
    var resp = await fetch(DOCS_API + path, options);
    if (!resp.ok) {
        var errorText = await resp.text();
        throw new Error("Docs API error (" + resp.status + "): " + errorText);
    }
    return await resp.json();
}

async function docsGet(params) {
    return await docsFetch("GET", "/documents/" + params.document_id);
}

async function docsCreate(params) {
    return await docsFetch("POST", "/documents", { title: params.title });
}

async function docsInsertText(params) {
    var index = params.index || 1;
    return await docsFetch("POST", "/documents/" + params.document_id + ":batchUpdate", {
        requests: [{
            insertText: {
                location: { index: index },
                text: params.text
            }
        }]
    });
}

async function docsAppendText(params) {
    var doc = await docsFetch("GET", "/documents/" + params.document_id);
    var endIndex = doc.body && doc.body.content
        ? doc.body.content[doc.body.content.length - 1].endIndex - 1
        : 1;

    return await docsFetch("POST", "/documents/" + params.document_id + ":batchUpdate", {
        requests: [{
            insertText: {
                location: { index: endIndex },
                text: params.text
            }
        }]
    });
}

async function docsGetText(params) {
    var doc = await docsFetch("GET", "/documents/" + params.document_id);
    var text = extractDocText(doc);
    return {
        title: doc.title,
        text: text,
        documentId: doc.documentId
    };
}

async function docsBatchUpdate(params) {
    return await docsFetch("POST", "/documents/" + params.document_id + ":batchUpdate", {
        requests: params.requests
    });
}

function extractDocText(doc) {
    var text = "";
    if (!doc.body || !doc.body.content) return text;

    for (var i = 0; i < doc.body.content.length; i++) {
        var element = doc.body.content[i];
        if (element.paragraph) {
            for (var j = 0; j < (element.paragraph.elements || []).length; j++) {
                var pe = element.paragraph.elements[j];
                if (pe.textRun && pe.textRun.content) {
                    text += pe.textRun.content;
                }
            }
        }
    }
    return text;
}
