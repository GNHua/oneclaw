/**
 * Google Drive tool group for OneClawShadow.
 * Uses FileTransferBridge for binary file operations.
 */

var DRIVE_API = "https://www.googleapis.com/drive/v3";
var UPLOAD_API = "https://www.googleapis.com/upload/drive/v3";
var DETAIL_FIELDS = "id,name,mimeType,size,modifiedTime,parents,webViewLink,webContentLink";

async function driveFetch(method, path, body) {
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
    var resp = await fetch(DRIVE_API + path, options);
    if (!resp.ok) {
        var errorText = await resp.text();
        throw new Error("Drive API error (" + resp.status + "): " + errorText);
    }
    if (resp.status === 204) return { success: true };
    return await resp.json();
}

async function driveListFiles(params) {
    var query = "?fields=files(" + DETAIL_FIELDS + "),nextPageToken";
    var qParts = ["trashed=false"];
    if (params.parent_id) {
        qParts.push("'" + params.parent_id + "' in parents");
    }
    if (params.query) {
        qParts.push(params.query);
    }
    if (qParts.length > 0) {
        query += "&q=" + encodeURIComponent(qParts.join(" and "));
    }
    if (params.max_results) query += "&pageSize=" + params.max_results;
    if (params.page_token) query += "&pageToken=" + encodeURIComponent(params.page_token);

    var data = await driveFetch("GET", "/files" + query);
    return { files: data.files || [], nextPageToken: data.nextPageToken };
}

async function driveGetFile(params) {
    return await driveFetch("GET", "/files/" + params.file_id + "?fields=" + DETAIL_FIELDS);
}

async function driveDownload(params) {
    var token = await google.getAccessToken();
    var fileId = params.file_id;
    var savePath = params.save_path;

    var result = await downloadToFile(
        DRIVE_API + "/files/" + fileId + "?alt=media",
        savePath,
        { "Authorization": "Bearer " + token }
    );
    if (!result.success) {
        throw new Error("Download failed: " + result.error);
    }
    return { path: result.path, size: result.size };
}

async function driveUpload(params) {
    var token = await google.getAccessToken();
    var metadata = {
        name: params.name,
        mimeType: params.mime_type || "application/octet-stream"
    };
    if (params.parent_id) {
        metadata.parents = [params.parent_id];
    }

    var result = await uploadMultipart(
        UPLOAD_API + "/files?uploadType=multipart&fields=" + DETAIL_FIELDS,
        [
            { type: "json", contentType: "application/json", body: JSON.stringify(metadata) },
            { type: "file", contentType: metadata.mimeType, path: params.file_path }
        ],
        { "Authorization": "Bearer " + token }
    );
    if (!result.ok) {
        throw new Error("Upload failed: " + (result.error || result.body));
    }
    return JSON.parse(result.body);
}

async function driveCreateFolder(params) {
    var metadata = {
        name: params.name,
        mimeType: "application/vnd.google-apps.folder"
    };
    if (params.parent_id) {
        metadata.parents = [params.parent_id];
    }
    return await driveFetch("POST", "/files?fields=" + DETAIL_FIELDS, metadata);
}

async function driveDeleteFile(params) {
    var token = await google.getAccessToken();
    var resp = await fetch(DRIVE_API + "/files/" + params.file_id, {
        method: "DELETE",
        headers: { "Authorization": "Bearer " + token }
    });
    if (!resp.ok && resp.status !== 204) {
        var errorText = await resp.text();
        throw new Error("Drive API error (" + resp.status + "): " + errorText);
    }
    return { success: true };
}

async function driveRenameFile(params) {
    return await driveFetch("PATCH", "/files/" + params.file_id + "?fields=" + DETAIL_FIELDS, {
        name: params.new_name
    });
}

async function driveMoveFile(params) {
    var existing = await driveFetch("GET", "/files/" + params.file_id + "?fields=parents");
    var oldParents = (existing.parents || []).join(",");
    return await driveFetch("PATCH",
        "/files/" + params.file_id + "?addParents=" + params.new_parent_id +
        "&removeParents=" + oldParents + "&fields=" + DETAIL_FIELDS
    );
}

async function driveShareFile(params) {
    var sendNotification = params.send_notification !== false;
    var token = await google.getAccessToken();
    var resp = await fetch(
        DRIVE_API + "/files/" + params.file_id + "/permissions?sendNotificationEmail=" + sendNotification,
        {
            method: "POST",
            headers: {
                "Authorization": "Bearer " + token,
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                type: "user",
                role: params.role,
                emailAddress: params.email
            })
        }
    );
    if (!resp.ok) {
        var errorText = await resp.text();
        throw new Error("Drive API error (" + resp.status + "): " + errorText);
    }
    return await resp.json();
}

async function driveExport(params) {
    var token = await google.getAccessToken();
    var url = DRIVE_API + "/files/" + params.file_id + "/export?mimeType=" + encodeURIComponent(params.mime_type);

    var result = await downloadToFile(url, params.save_path, { "Authorization": "Bearer " + token });
    if (!result.success) {
        throw new Error("Export failed: " + result.error);
    }
    return { path: result.path, size: result.size };
}

async function driveGetStorageQuota(params) {
    var data = await driveFetch("GET", "/about?fields=storageQuota,user");
    return {
        limit: data.storageQuota && data.storageQuota.limit,
        usage: data.storageQuota && data.storageQuota.usage,
        usageInDrive: data.storageQuota && data.storageQuota.usageInDrive,
        usageInDriveTrash: data.storageQuota && data.storageQuota.usageInDriveTrash
    };
}

async function driveListShared(params) {
    var maxResults = params.max_results || 20;
    var query = "?fields=files(" + DETAIL_FIELDS + ")&q=sharedWithMe%20and%20trashed%3Dfalse&pageSize=" + maxResults;
    var data = await driveFetch("GET", "/files" + query);
    return { files: data.files || [] };
}

async function driveSearch(params) {
    var maxResults = params.max_results || 20;
    var q = encodeURIComponent("name contains '" + params.query.replace(/'/g, "\\'") + "' and trashed=false");
    var query = "?fields=files(" + DETAIL_FIELDS + ")&q=" + q + "&pageSize=" + maxResults;
    var data = await driveFetch("GET", "/files" + query);
    return { files: data.files || [] };
}
