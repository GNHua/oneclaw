var PEOPLE_API = "https://people.googleapis.com/v1";

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

async function peopleFetch(method, path, body) {
    var token = await getToken();
    var headers = { "Authorization": "Bearer " + token };
    var raw = await palmclaw.http.fetch(
        method,
        PEOPLE_API + path,
        body || null,
        "application/json",
        headers
    );
    var resp = JSON.parse(raw);
    if (resp.status >= 400) {
        throw new Error("People API error (HTTP " + resp.status + "): " + resp.body);
    }
    if (!resp.body || resp.body.length === 0) return {};
    return JSON.parse(resp.body);
}

function formatContact(person) {
    var name = "";
    if (person.names && person.names.length > 0) {
        name = person.names[0].displayName || "";
    }

    var emails = [];
    if (person.emailAddresses) {
        for (var i = 0; i < person.emailAddresses.length; i++) {
            emails.push(person.emailAddresses[i].value);
        }
    }

    var phones = [];
    if (person.phoneNumbers) {
        for (var i = 0; i < person.phoneNumbers.length; i++) {
            phones.push(person.phoneNumbers[i].value);
        }
    }

    var org = "";
    if (person.organizations && person.organizations.length > 0) {
        var o = person.organizations[0];
        var parts = [];
        if (o.title) parts.push(o.title);
        if (o.name) parts.push(o.name);
        org = parts.join(" at ");
    }

    var result = {
        resourceName: person.resourceName || "",
        name: name
    };
    if (emails.length > 0) result.emails = emails;
    if (phones.length > 0) result.phones = phones;
    if (org) result.organization = org;

    return result;
}

function formatContactDetailed(person) {
    var basic = formatContact(person);

    if (person.addresses && person.addresses.length > 0) {
        basic.addresses = person.addresses.map(function(a) {
            return {
                type: a.type || "",
                formattedValue: a.formattedValue || ""
            };
        });
    }

    if (person.biographies && person.biographies.length > 0) {
        basic.biography = person.biographies[0].value || "";
    }

    if (person.birthdays && person.birthdays.length > 0) {
        var bd = person.birthdays[0].date;
        if (bd) {
            var parts = [];
            if (bd.year) parts.push(bd.year);
            if (bd.month) parts.push(bd.month < 10 ? "0" + bd.month : "" + bd.month);
            if (bd.day) parts.push(bd.day < 10 ? "0" + bd.day : "" + bd.day);
            basic.birthday = parts.join("-");
        }
    }

    if (person.urls && person.urls.length > 0) {
        basic.urls = person.urls.map(function(u) {
            return u.value;
        });
    }

    return basic;
}

async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "contacts_search": {
                var maxResults = args.max_results || 10;
                if (maxResults > 30) maxResults = 30;
                var path = "/people:searchContacts?" +
                    "query=" + encodeURIComponent(args.query) +
                    "&readMask=names,emailAddresses,phoneNumbers,organizations" +
                    "&pageSize=" + maxResults;

                var data = await peopleFetch("GET", path);
                var results = data.results || [];

                if (results.length === 0) {
                    return { output: "No contacts found matching: " + args.query };
                }

                var contacts = [];
                for (var i = 0; i < results.length; i++) {
                    if (results[i].person) {
                        contacts.push(formatContact(results[i].person));
                    }
                }

                return { output: JSON.stringify(contacts, null, 2) };
            }

            case "contacts_list": {
                var maxResults = args.max_results || 25;
                if (maxResults > 100) maxResults = 100;
                var path = "/people/me/connections?" +
                    "personFields=names,emailAddresses,phoneNumbers,organizations" +
                    "&pageSize=" + maxResults;

                if (args.page_token) {
                    path += "&pageToken=" + encodeURIComponent(args.page_token);
                }

                var data = await peopleFetch("GET", path);
                var connections = data.connections || [];

                if (connections.length === 0) {
                    return { output: "No contacts found." };
                }

                var contacts = [];
                for (var i = 0; i < connections.length; i++) {
                    contacts.push(formatContact(connections[i]));
                }

                var result = { contacts: contacts, totalPeople: data.totalPeople || 0 };
                if (data.nextPageToken) {
                    result.nextPageToken = data.nextPageToken;
                }

                return { output: JSON.stringify(result, null, 2) };
            }

            case "contacts_get": {
                var resourceName = args.resource_name;
                var path = "/" + resourceName +
                    "?personFields=names,emailAddresses,phoneNumbers,organizations,addresses,biographies,birthdays,urls";

                var data = await peopleFetch("GET", path);
                var contact = formatContactDetailed(data);

                return { output: JSON.stringify(contact, null, 2) };
            }

            case "contacts_create": {
                var body = {
                    names: [{ givenName: args.given_name }]
                };
                if (args.family_name) {
                    body.names[0].familyName = args.family_name;
                }
                if (args.email) {
                    body.emailAddresses = [{ value: args.email }];
                }
                if (args.phone) {
                    body.phoneNumbers = [{ value: args.phone }];
                }

                var data = await peopleFetch("POST", "/people:createContact",
                    JSON.stringify(body));

                var displayName = args.given_name;
                if (args.family_name) displayName += " " + args.family_name;

                return {
                    output: "Contact created: " + displayName +
                        "\nResource: " + (data.resourceName || "")
                };
            }

            case "contacts_update": {
                var resourceName = args.resource_name;

                // Fetch existing contact to get etag and current data
                var existing = await peopleFetch("GET",
                    "/" + resourceName +
                    "?personFields=names,emailAddresses,phoneNumbers");

                var body = {
                    etag: existing.etag
                };

                var updateFields = [];

                // Handle names
                if (args.given_name !== undefined || args.family_name !== undefined) {
                    var currentName = (existing.names && existing.names.length > 0)
                        ? existing.names[0] : {};
                    body.names = [{
                        givenName: args.given_name !== undefined ? args.given_name : (currentName.givenName || ""),
                        familyName: args.family_name !== undefined ? args.family_name : (currentName.familyName || "")
                    }];
                    updateFields.push("names");
                }

                // Handle email
                if (args.email !== undefined) {
                    body.emailAddresses = [{ value: args.email }];
                    updateFields.push("emailAddresses");
                }

                // Handle phone
                if (args.phone !== undefined) {
                    body.phoneNumbers = [{ value: args.phone }];
                    updateFields.push("phoneNumbers");
                }

                if (updateFields.length === 0) {
                    return { output: "No fields to update." };
                }

                var path = "/" + resourceName + ":updateContact?" +
                    "updatePersonFields=" + updateFields.join(",");

                var data = await peopleFetch("PATCH", path,
                    JSON.stringify(body));

                var updatedName = "";
                if (data.names && data.names.length > 0) {
                    updatedName = data.names[0].displayName || "";
                }

                return { output: "Contact updated: " + updatedName };
            }

            case "contacts_delete": {
                var resourceName = args.resource_name;
                await peopleFetch("DELETE", "/" + resourceName + ":deleteContact");
                return { output: "Contact deleted: " + resourceName };
            }

            case "contacts_directory": {
                var maxResults = args.max_results || 25;
                if (maxResults > 100) maxResults = 100;

                try {
                    var path = "/people:listDirectoryPeople?" +
                        "readMask=names,emailAddresses,phoneNumbers" +
                        "&sources=DIRECTORY_SOURCE_TYPE_DOMAIN_PROFILE" +
                        "&pageSize=" + maxResults;

                    var data = await peopleFetch("GET", path);
                    var people = data.people || [];

                    if (people.length === 0) {
                        return { output: "No directory contacts found." };
                    }

                    var contacts = [];
                    for (var i = 0; i < people.length; i++) {
                        contacts.push(formatContact(people[i]));
                    }

                    return { output: JSON.stringify(contacts, null, 2) };
                } catch (e) {
                    if (e.message.indexOf("403") !== -1 || e.message.indexOf("PERMISSION_DENIED") !== -1) {
                        return { output: "Directory listing is only available for Google Workspace accounts. This appears to be a consumer Google account." };
                    }
                    throw e;
                }
            }

            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        palmclaw.log.error("contacts error: " + e.message);
        return { error: e.message };
    }
}
