/**
 * Google Gmail Settings tool group for OneClawShadow.
 */

var GMAIL_SETTINGS_API = "https://www.googleapis.com/gmail/v1/users/me/settings";

async function gmailSettingsFetch(method, path, body) {
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
    var resp = await fetch(GMAIL_SETTINGS_API + path, options);
    if (!resp.ok) {
        var errorText = await resp.text();
        throw new Error("Gmail Settings API error (" + resp.status + "): " + errorText);
    }
    return await resp.json();
}

async function gmailSettingsGetVacation(params) {
    return await gmailSettingsFetch("GET", "/vacation");
}

async function gmailSettingsUpdateVacation(params) {
    var body = {
        enableAutoReply: params.enable_auto_reply
    };
    if (params.start_time !== undefined) body.startTime = params.start_time;
    if (params.end_time !== undefined) body.endTime = params.end_time;
    if (params.subject !== undefined) body.responseSubject = params.subject;
    if (params.body_html !== undefined) body.responseBodyHtml = params.body_html;
    if (params.restrict_to_domain !== undefined) body.restrictToDomain = params.restrict_to_domain;
    if (params.restrict_to_contacts !== undefined) body.restrictToContacts = params.restrict_to_contacts;
    return await gmailSettingsFetch("PUT", "/vacation", body);
}

async function gmailSettingsGetImap(params) {
    return await gmailSettingsFetch("GET", "/imap");
}

async function gmailSettingsUpdateImap(params) {
    var body = {};
    if (params.enabled !== undefined) body.enabled = params.enabled;
    if (params.auto_expunge !== undefined) body.autoExpunge = params.auto_expunge;
    if (params.expunge_behavior !== undefined) body.expungeBehavior = params.expunge_behavior;
    if (params.max_folder_size !== undefined) body.maxFolderSize = params.max_folder_size;
    return await gmailSettingsFetch("PUT", "/imap", body);
}

async function gmailSettingsListFilters(params) {
    var data = await gmailSettingsFetch("GET", "/filters");
    return { filters: data.filter || [] };
}

async function gmailSettingsCreateFilter(params) {
    var criteria = {};
    if (params.from) criteria.from = params.from;
    if (params.to) criteria.to = params.to;
    if (params.subject) criteria.subject = params.subject;
    if (params.query) criteria.query = params.query;

    var action = {};
    if (params.add_label_ids) action.addLabelIds = params.add_label_ids;
    if (params.remove_label_ids) action.removeLabelIds = params.remove_label_ids;

    return await gmailSettingsFetch("POST", "/filters", {
        criteria: criteria,
        action: action
    });
}

async function gmailSettingsDeleteFilter(params) {
    var token = await google.getAccessToken();
    var resp = await fetch(GMAIL_SETTINGS_API + "/filters/" + params.filter_id, {
        method: "DELETE",
        headers: { "Authorization": "Bearer " + token }
    });
    if (!resp.ok) {
        var errorText = await resp.text();
        throw new Error("Gmail Settings API error (" + resp.status + "): " + errorText);
    }
    return { success: true };
}

async function gmailSettingsListForwardingAddresses(params) {
    var data = await gmailSettingsFetch("GET", "/forwardingAddresses");
    return { forwardingAddresses: data.forwardingAddresses || [] };
}

async function gmailSettingsGetAutoForwarding(params) {
    return await gmailSettingsFetch("GET", "/autoForwarding");
}

async function gmailSettingsListSendAs(params) {
    var data = await gmailSettingsFetch("GET", "/sendAs");
    return { sendAs: data.sendAs || [] };
}

async function gmailSettingsGetLanguage(params) {
    return await gmailSettingsFetch("GET", "/language");
}
