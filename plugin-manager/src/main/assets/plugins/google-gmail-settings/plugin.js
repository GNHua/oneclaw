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
    if (resp.status >= 400) {
        throw new Error("Gmail API error (HTTP " + resp.status + "): " + resp.body);
    }
    if (!resp.body || resp.body.length === 0) return {};
    return JSON.parse(resp.body);
}

async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "gmail_list_filters": {
                var data = await gmailFetch("GET", "/settings/filters");
                var filters = (data.filter || []).map(function(f) {
                    return {
                        id: f.id,
                        criteria: f.criteria || {},
                        action: f.action || {}
                    };
                });
                if (filters.length === 0) {
                    return { output: "No filters found." };
                }
                return { output: JSON.stringify(filters, null, 2) };
            }

            case "gmail_create_filter": {
                var criteria = {};
                if (args.from) criteria.from = args.from;
                if (args.to) criteria.to = args.to;
                if (args.subject) criteria.subject = args.subject;
                if (args.query) criteria.query = args.query;
                if (args.has_attachment === true) criteria.hasAttachment = true;

                var action = {};
                if (args.add_labels) action.addLabelIds = args.add_labels;
                if (args.remove_labels) action.removeLabelIds = args.remove_labels;
                if (args.forward_to) action.forward = args.forward_to;

                var filterBody = {
                    criteria: criteria,
                    action: action
                };

                var data = await gmailFetch("POST", "/settings/filters",
                    JSON.stringify(filterBody));
                return { output: "Filter created. ID: " + data.id };
            }

            case "gmail_delete_filter": {
                await gmailFetch("DELETE", "/settings/filters/" + args.filter_id);
                return { output: "Filter deleted: " + args.filter_id };
            }

            case "gmail_get_vacation": {
                var data = await gmailFetch("GET", "/settings/vacation");
                var result = {
                    enableAutoReply: data.enableAutoReply || false,
                    responseSubject: data.responseSubject || "",
                    responseBodyPlainText: data.responseBodyPlainText || "",
                    startTime: data.startTime || null,
                    endTime: data.endTime || null,
                    restrictToContacts: data.restrictToContacts || false,
                    restrictToDomain: data.restrictToDomain || false
                };
                return { output: JSON.stringify(result, null, 2) };
            }

            case "gmail_set_vacation": {
                var vacationBody = {
                    enableAutoReply: args.enable === true
                };
                if (args.subject) vacationBody.responseSubject = args.subject;
                if (args.body) vacationBody.responseBodyPlainText = args.body;
                if (args.start_time) vacationBody.startTime = args.start_time;
                if (args.end_time) vacationBody.endTime = args.end_time;
                if (args.restrict_to_contacts !== undefined) {
                    vacationBody.restrictToContacts = args.restrict_to_contacts === true;
                }
                if (args.restrict_to_domain !== undefined) {
                    vacationBody.restrictToDomain = args.restrict_to_domain === true;
                }

                var data = await gmailFetch("PUT", "/settings/vacation",
                    JSON.stringify(vacationBody));
                var status = data.enableAutoReply ? "enabled" : "disabled";
                return { output: "Vacation responder " + status + "." };
            }

            case "gmail_list_forwarding": {
                var data = await gmailFetch("GET", "/settings/forwardingAddresses");
                var addresses = (data.forwardingAddresses || []).map(function(a) {
                    return {
                        forwardingEmail: a.forwardingEmail,
                        verificationStatus: a.verificationStatus
                    };
                });
                if (addresses.length === 0) {
                    return { output: "No forwarding addresses configured." };
                }
                return { output: JSON.stringify(addresses, null, 2) };
            }

            case "gmail_add_forwarding": {
                var data = await gmailFetch("POST", "/settings/forwardingAddresses",
                    JSON.stringify({ forwardingEmail: args.email }));
                return { output: "Forwarding address added: " + data.forwardingEmail + " (status: " + data.verificationStatus + "). A verification email has been sent." };
            }

            case "gmail_get_auto_forward": {
                var data = await gmailFetch("GET", "/settings/autoForwarding");
                var result = {
                    enabled: data.enabled || false,
                    emailAddress: data.emailAddress || "",
                    disposition: data.disposition || ""
                };
                return { output: JSON.stringify(result, null, 2) };
            }

            case "gmail_set_auto_forward": {
                var forwardBody = {
                    enabled: args.enabled === true
                };
                if (args.email) forwardBody.emailAddress = args.email;
                if (args.disposition) forwardBody.disposition = args.disposition;

                await gmailFetch("PUT", "/settings/autoForwarding",
                    JSON.stringify(forwardBody));
                var status = forwardBody.enabled ? "enabled" : "disabled";
                return { output: "Auto-forwarding " + status + "." };
            }

            case "gmail_list_send_as": {
                var data = await gmailFetch("GET", "/settings/sendAs");
                var aliases = (data.sendAs || []).map(function(s) {
                    return {
                        sendAsEmail: s.sendAsEmail,
                        displayName: s.displayName || "",
                        replyToAddress: s.replyToAddress || "",
                        isPrimary: s.isPrimary || false,
                        isDefault: s.isDefault || false,
                        verificationStatus: s.verificationStatus || ""
                    };
                });
                return { output: JSON.stringify(aliases, null, 2) };
            }

            case "gmail_list_delegates": {
                var data = await gmailFetch("GET", "/settings/delegates");
                var delegates = (data.delegates || []).map(function(d) {
                    return {
                        delegateEmail: d.delegateEmail,
                        verificationStatus: d.verificationStatus
                    };
                });
                if (delegates.length === 0) {
                    return { output: "No delegates configured." };
                }
                return { output: JSON.stringify(delegates, null, 2) };
            }

            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        oneclaw.log.error("gmail-settings error: " + e.message);
        return { error: e.message };
    }
}
