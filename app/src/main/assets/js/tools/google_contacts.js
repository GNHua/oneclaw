/**
 * Google Contacts (People API) tool group for OneClawShadow.
 */

var PEOPLE_API = "https://people.googleapis.com/v1";

var PERSON_FIELDS = "names,emailAddresses,phoneNumbers,organizations,biographies,addresses";

async function peopleFetch(method, path, body) {
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
    var resp = await fetch(PEOPLE_API + path, options);
    if (!resp.ok) {
        var errorText = await resp.text();
        throw new Error("People API error (" + resp.status + "): " + errorText);
    }
    if (resp.status === 204) return { success: true };
    return await resp.json();
}

async function contactsList(params) {
    var maxResults = params.max_results || 20;
    var query = "?personFields=" + PERSON_FIELDS + "&pageSize=" + maxResults;
    if (params.page_token) query += "&pageToken=" + encodeURIComponent(params.page_token);
    var data = await peopleFetch("GET", "/people/me/connections" + query);
    return {
        contacts: (data.connections || []).map(formatContact),
        nextPageToken: data.nextPageToken
    };
}

async function contactsSearch(params) {
    var maxResults = params.max_results || 10;
    var query = "?query=" + encodeURIComponent(params.query) +
        "&pageSize=" + maxResults +
        "&readMask=" + PERSON_FIELDS;
    var data = await peopleFetch("GET", "/people:searchContacts" + query);
    return {
        contacts: (data.results || []).map(function(r) { return formatContact(r.person); })
    };
}

async function contactsGet(params) {
    var query = "?personFields=" + PERSON_FIELDS;
    var data = await peopleFetch("GET", "/" + params.resource_name + query);
    return formatContact(data);
}

async function contactsCreate(params) {
    var body = {
        names: [{
            givenName: params.given_name,
            familyName: params.family_name || ""
        }]
    };
    if (params.email) {
        body.emailAddresses = [{ value: params.email, type: "home" }];
    }
    if (params.phone) {
        body.phoneNumbers = [{ value: params.phone, type: "mobile" }];
    }
    if (params.organization || params.job_title) {
        body.organizations = [{
            name: params.organization || "",
            title: params.job_title || ""
        }];
    }
    var data = await peopleFetch("POST", "/people:createContact", body);
    return formatContact(data);
}

async function contactsUpdate(params) {
    var existing = await peopleFetch("GET", "/" + params.resource_name + "?personFields=" + PERSON_FIELDS);

    var updateFields = [];
    if (params.given_name !== undefined || params.family_name !== undefined) {
        existing.names = [{
            givenName: params.given_name || (existing.names && existing.names[0] && existing.names[0].givenName) || "",
            familyName: params.family_name || (existing.names && existing.names[0] && existing.names[0].familyName) || ""
        }];
        updateFields.push("names");
    }
    if (params.email !== undefined) {
        existing.emailAddresses = [{ value: params.email, type: "home" }];
        updateFields.push("emailAddresses");
    }
    if (params.phone !== undefined) {
        existing.phoneNumbers = [{ value: params.phone, type: "mobile" }];
        updateFields.push("phoneNumbers");
    }

    var query = "?updatePersonFields=" + updateFields.join(",");
    var data = await peopleFetch("PATCH", "/" + params.resource_name + ":updateContact" + query, existing);
    return formatContact(data);
}

async function contactsDelete(params) {
    var token = await google.getAccessToken();
    var resp = await fetch(PEOPLE_API + "/" + params.resource_name + ":deleteContact", {
        method: "DELETE",
        headers: { "Authorization": "Bearer " + token }
    });
    if (!resp.ok && resp.status !== 204) {
        var errorText = await resp.text();
        throw new Error("People API error (" + resp.status + "): " + errorText);
    }
    return { success: true };
}

async function contactsListDirectory(params) {
    var maxResults = params.max_results || 10;
    var query = "?query=" + encodeURIComponent(params.query) +
        "&pageSize=" + maxResults +
        "&readMask=" + PERSON_FIELDS;
    var data = await peopleFetch("GET", "/people:searchDirectoryPeople" + query);
    return {
        contacts: (data.people || []).map(formatContact)
    };
}

function formatContact(person) {
    if (!person) return {};
    return {
        resourceName: person.resourceName,
        etag: person.etag,
        name: person.names && person.names[0] ? person.names[0].displayName : "",
        givenName: person.names && person.names[0] ? person.names[0].givenName : "",
        familyName: person.names && person.names[0] ? person.names[0].familyName : "",
        email: person.emailAddresses && person.emailAddresses[0] ? person.emailAddresses[0].value : "",
        phone: person.phoneNumbers && person.phoneNumbers[0] ? person.phoneNumbers[0].value : "",
        organization: person.organizations && person.organizations[0] ? person.organizations[0].name : "",
        jobTitle: person.organizations && person.organizations[0] ? person.organizations[0].title : ""
    };
}
