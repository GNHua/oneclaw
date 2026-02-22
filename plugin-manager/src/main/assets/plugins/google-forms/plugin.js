var FORMS_API = "https://forms.googleapis.com/v1";

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

async function formsFetch(method, path, body) {
    var token = await getToken();
    var headers = { "Authorization": "Bearer " + token };
    var raw = await oneclaw.http.fetch(
        method,
        FORMS_API + path,
        body || null,
        "application/json",
        headers
    );
    var resp = JSON.parse(raw);
    if (resp.error) {
        throw new Error("Forms request failed: " + resp.error);
    }
    if (resp.status >= 400) {
        throw new Error("Forms API error (HTTP " + resp.status + "): " + resp.body);
    }
    if (!resp.body || resp.body.length === 0) return {};
    return JSON.parse(resp.body);
}

async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "forms_get": {
                var data = await formsFetch("GET", "/forms/" + args.form_id);
                var result = {
                    formId: data.formId,
                    title: data.info ? data.info.title : "",
                    description: data.info ? (data.info.description || "") : "",
                    documentTitle: data.info ? (data.info.documentTitle || "") : "",
                    responderUri: data.responderUri || "",
                    items: (data.items || []).map(function(item) {
                        var q = {
                            itemId: item.itemId,
                            title: item.title || ""
                        };
                        if (item.questionItem && item.questionItem.question) {
                            var question = item.questionItem.question;
                            q.questionId = question.questionId;
                            q.required = question.required || false;
                            if (question.choiceQuestion) {
                                q.type = "choice";
                                q.options = (question.choiceQuestion.options || []).map(function(o) { return o.value; });
                            } else if (question.textQuestion) {
                                q.type = "text";
                            } else if (question.scaleQuestion) {
                                q.type = "scale";
                                q.low = question.scaleQuestion.low;
                                q.high = question.scaleQuestion.high;
                            } else if (question.dateQuestion) {
                                q.type = "date";
                            } else if (question.timeQuestion) {
                                q.type = "time";
                            }
                        }
                        return q;
                    })
                };
                return { output: JSON.stringify(result, null, 2) };
            }

            case "forms_list_responses": {
                var path = "/forms/" + args.form_id + "/responses";
                if (args.max_results) path += "?pageSize=" + args.max_results;
                var data = await formsFetch("GET", path);
                var responses = (data.responses || []).map(function(r) {
                    return {
                        responseId: r.responseId,
                        createTime: r.createTime,
                        lastSubmittedTime: r.lastSubmittedTime,
                        answersCount: r.answers ? Object.keys(r.answers).length : 0
                    };
                });
                return { output: JSON.stringify({ totalResponses: responses.length, responses: responses }, null, 2) };
            }

            case "forms_get_response": {
                var data = await formsFetch("GET",
                    "/forms/" + args.form_id + "/responses/" + args.response_id);
                return { output: JSON.stringify(data, null, 2) };
            }

            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        var msg = (e && e.message) ? e.message : String(e || "Unknown error");
        oneclaw.log.error("forms error: " + msg);
        return { error: msg };
    }
}
