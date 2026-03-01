/**
 * Google Forms tool group for OneClawShadow.
 * Read-only: form structure and responses.
 */

var FORMS_API = "https://forms.googleapis.com/v1";

async function formsFetch(method, path, body) {
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
    var resp = await fetch(FORMS_API + path, options);
    if (!resp.ok) {
        var errorText = await resp.text();
        throw new Error("Forms API error (" + resp.status + "): " + errorText);
    }
    return await resp.json();
}

async function formsGet(params) {
    var form = await formsFetch("GET", "/forms/" + params.form_id);
    return {
        formId: form.formId,
        title: form.info && form.info.title,
        description: form.info && form.info.description,
        documentTitle: form.info && form.info.documentTitle,
        responderUri: form.responderUri,
        items: (form.items || []).map(function(item) {
            return {
                itemId: item.itemId,
                title: item.title,
                description: item.description,
                questionItem: item.questionItem ? {
                    question: {
                        questionId: item.questionItem.question && item.questionItem.question.questionId,
                        required: item.questionItem.question && item.questionItem.question.required,
                        type: getQuestionType(item.questionItem.question)
                    }
                } : null
            };
        })
    };
}

async function formsListResponses(params) {
    var query = "?";
    if (params.page_size) query += "pageSize=" + params.page_size + "&";
    if (params.page_token) query += "pageToken=" + encodeURIComponent(params.page_token) + "&";

    var data = await formsFetch("GET", "/forms/" + params.form_id + "/responses" + query);
    return {
        responses: data.responses || [],
        nextPageToken: data.nextPageToken,
        totalSize: data.totalSize
    };
}

async function formsGetResponse(params) {
    return await formsFetch("GET", "/forms/" + params.form_id + "/responses/" + params.response_id);
}

function getQuestionType(question) {
    if (!question) return "UNKNOWN";
    if (question.choiceQuestion) return "CHOICE";
    if (question.textQuestion) return "TEXT";
    if (question.scaleQuestion) return "SCALE";
    if (question.dateQuestion) return "DATE";
    if (question.timeQuestion) return "TIME";
    if (question.fileUploadQuestion) return "FILE_UPLOAD";
    if (question.rowQuestion) return "ROW";
    return "UNKNOWN";
}
