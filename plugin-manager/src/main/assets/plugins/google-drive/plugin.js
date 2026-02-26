var DRIVE_API = "https://www.googleapis.com/drive/v3";
var UPLOAD_API = "https://www.googleapis.com/upload/drive/v3";

var FILE_FIELDS = "files(id,name,mimeType,size,modifiedTime,parents)";
var DETAIL_FIELDS = "id,name,mimeType,size,modifiedTime,createdTime,parents,webViewLink,description";

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

async function driveFetch(method, path, body) {
    var token = await getToken();
    var headers = { "Authorization": "Bearer " + token };
    var raw = await oneclaw.http.fetch(
        method,
        DRIVE_API + path,
        body || null,
        "application/json",
        headers
    );
    var resp = JSON.parse(raw);
    if (resp.error) {
        throw new Error("Drive request failed: " + resp.error);
    }
    if (resp.status >= 400) {
        throw new Error("Drive API error (HTTP " + resp.status + "): " + resp.body);
    }
    if (!resp.body || resp.body.length === 0) return {};
    return JSON.parse(resp.body);
}

function formatFile(f) {
    return {
        id: f.id,
        name: f.name,
        mimeType: f.mimeType || "",
        size: f.size || null,
        modifiedTime: f.modifiedTime || "",
        parents: f.parents || []
    };
}

async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "drive_list": {
                var pageSize = Math.min(args.max_results || 20, 100);
                var orderBy = args.order_by || "modifiedTime desc";
                var path = "/files?fields=nextPageToken," + FILE_FIELDS +
                    "&pageSize=" + pageSize +
                    "&orderBy=" + encodeURIComponent(orderBy);
                if (args.query) {
                    path += "&q=" + encodeURIComponent(args.query);
                }
                if (args.page_token) {
                    path += "&pageToken=" + encodeURIComponent(args.page_token);
                }

                var data = await driveFetch("GET", path);
                var files = (data.files || []).map(formatFile);

                if (files.length === 0) {
                    return { output: "No files found." };
                }

                var result = { files: files };
                if (data.nextPageToken) {
                    result.nextPageToken = data.nextPageToken;
                }
                return { output: JSON.stringify(result, null, 2) };
            }

            case "drive_search": {
                var pageSize = Math.min(args.max_results || 20, 100);
                var query = "fullText contains " + JSON.stringify(args.text);
                var path = "/files?fields=nextPageToken," + FILE_FIELDS +
                    "&pageSize=" + pageSize +
                    "&q=" + encodeURIComponent(query);

                var data = await driveFetch("GET", path);
                var files = (data.files || []).map(formatFile);

                if (files.length === 0) {
                    return { output: "No files found matching: " + args.text };
                }

                return { output: JSON.stringify(files, null, 2) };
            }

            case "drive_get": {
                var data = await driveFetch("GET",
                    "/files/" + encodeURIComponent(args.file_id) +
                    "?fields=" + encodeURIComponent(DETAIL_FIELDS));

                var result = {
                    id: data.id,
                    name: data.name,
                    mimeType: data.mimeType || "",
                    size: data.size || null,
                    modifiedTime: data.modifiedTime || "",
                    createdTime: data.createdTime || "",
                    parents: data.parents || [],
                    webViewLink: data.webViewLink || "",
                    description: data.description || ""
                };
                return { output: JSON.stringify(result, null, 2) };
            }

            case "drive_mkdir": {
                var body = {
                    name: args.name,
                    mimeType: "application/vnd.google-apps.folder"
                };
                if (args.parent_id) {
                    body.parents = [args.parent_id];
                }

                var data = await driveFetch("POST", "/files",
                    JSON.stringify(body));

                return {
                    output: "Folder created: " + data.name +
                        "\nID: " + data.id
                };
            }

            case "drive_copy": {
                var body = {};
                if (args.name) {
                    body.name = args.name;
                }

                var data = await driveFetch("POST",
                    "/files/" + encodeURIComponent(args.file_id) + "/copy",
                    JSON.stringify(body));

                return {
                    output: "File copied: " + data.name +
                        "\nNew ID: " + data.id
                };
            }

            case "drive_rename": {
                var data = await driveFetch("PATCH",
                    "/files/" + encodeURIComponent(args.file_id),
                    JSON.stringify({ name: args.name }));

                return { output: "File renamed to: " + data.name };
            }

            case "drive_move": {
                var oldParent = args.old_parent_id;
                if (!oldParent) {
                    var fileMeta = await driveFetch("GET",
                        "/files/" + encodeURIComponent(args.file_id) +
                        "?fields=parents");
                    oldParent = (fileMeta.parents && fileMeta.parents.length > 0)
                        ? fileMeta.parents[0]
                        : "";
                }

                var path = "/files/" + encodeURIComponent(args.file_id) +
                    "?addParents=" + encodeURIComponent(args.new_parent_id);
                if (oldParent) {
                    path += "&removeParents=" + encodeURIComponent(oldParent);
                }

                var data = await driveFetch("PATCH", path,
                    JSON.stringify({}));

                return { output: "File moved successfully. ID: " + data.id };
            }

            case "drive_delete": {
                await driveFetch("PATCH",
                    "/files/" + encodeURIComponent(args.file_id),
                    JSON.stringify({ trashed: true }));

                return { output: "File moved to Trash. ID: " + args.file_id };
            }

            case "drive_share": {
                var permType = args.type || "user";
                var body = {
                    role: args.role,
                    type: permType,
                    emailAddress: args.email
                };

                var data = await driveFetch("POST",
                    "/files/" + encodeURIComponent(args.file_id) + "/permissions",
                    JSON.stringify(body));

                return {
                    output: "Shared with " + args.email + " as " + args.role +
                        "\nPermission ID: " + data.id
                };
            }

            case "drive_permissions": {
                var permFields = "permissions(id,emailAddress,role,type,displayName)";
                var data = await driveFetch("GET",
                    "/files/" + encodeURIComponent(args.file_id) +
                    "/permissions?fields=" + encodeURIComponent(permFields));

                var permissions = (data.permissions || []).map(function(p) {
                    return {
                        id: p.id,
                        emailAddress: p.emailAddress || "",
                        role: p.role || "",
                        type: p.type || "",
                        displayName: p.displayName || ""
                    };
                });

                return { output: JSON.stringify(permissions, null, 2) };
            }

            case "drive_download": {
                var savePath = args.save_path || args.path;
                if (!savePath) throw new Error("save_path is required");
                var token = await getToken();
                // Fetch file metadata to detect Google Workspace files
                var meta = await driveFetch("GET",
                    "/files/" + encodeURIComponent(args.file_id) +
                    "?fields=mimeType");
                var mime = meta.mimeType || "";
                if (mime.indexOf("application/vnd.google-apps.") === 0) {
                    // Google Workspace file -- export instead of download
                    var exportMime;
                    if (mime === "application/vnd.google-apps.spreadsheet") {
                        exportMime = "text/csv";
                    } else {
                        // Docs, Slides, Drawings, and others export as PDF
                        exportMime = "application/pdf";
                    }
                    var exportUrl = DRIVE_API + "/files/" + encodeURIComponent(args.file_id) +
                        "/export?mimeType=" + encodeURIComponent(exportMime);
                    var headers = { "Authorization": "Bearer " + token };
                    await oneclaw.http.downloadToFile(exportUrl, savePath, headers);
                    return { output: "Google Workspace file exported as " + exportMime + " to workspace: " + savePath };
                }
                var url = DRIVE_API + "/files/" + encodeURIComponent(args.file_id) + "?alt=media";
                var headers = { "Authorization": "Bearer " + token };
                await oneclaw.http.downloadToFile(url, savePath, headers);
                return { output: "File downloaded to workspace: " + savePath };
            }

            case "drive_upload": {
                var token = await getToken();
                var url = UPLOAD_API + "/files?uploadType=multipart";
                var metadata = { name: args.name };
                if (args.parent_id) {
                    metadata.parents = [args.parent_id];
                }
                var parts = [
                    {
                        name: "metadata",
                        body: JSON.stringify(metadata),
                        contentType: "application/json"
                    },
                    {
                        name: "file",
                        filePath: args.file_path,
                        contentType: args.mime_type
                    }
                ];
                var headers = { "Authorization": "Bearer " + token };
                var raw = await oneclaw.http.uploadMultipart(url, parts, headers);
                var data = JSON.parse(raw);
                if (data.error) {
                    throw new Error("Drive upload error: " + JSON.stringify(data.error));
                }
                return {
                    output: "File uploaded: " + data.name +
                        "\nID: " + data.id
                };
            }

            case "drive_export": {
                var savePath = args.save_path || args.path;
                if (!savePath) throw new Error("save_path is required");
                var token = await getToken();
                var url = DRIVE_API + "/files/" + encodeURIComponent(args.file_id) +
                    "/export?mimeType=" + encodeURIComponent(args.mime_type);
                var headers = { "Authorization": "Bearer " + token };
                await oneclaw.http.downloadToFile(url, savePath, headers);
                return { output: "File exported to workspace: " + savePath };
            }

            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        var msg = (e && e.message) ? e.message : String(e || "Unknown error");
        oneclaw.log.error("drive error: " + msg);
        return { error: msg };
    }
}
