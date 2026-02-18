var TASKS_API = "https://tasks.googleapis.com/tasks/v1";

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

async function tasksFetch(method, path, body) {
    var token = await getToken();
    var headers = { "Authorization": "Bearer " + token };
    var raw = await oneclaw.http.fetch(
        method,
        TASKS_API + path,
        body || null,
        "application/json",
        headers
    );
    var resp = JSON.parse(raw);
    if (resp.status >= 400) {
        throw new Error("Tasks API error (HTTP " + resp.status + "): " + resp.body);
    }
    if (!resp.body || resp.body.length === 0) return {};
    return JSON.parse(resp.body);
}

function formatTask(task) {
    return {
        id: task.id,
        title: task.title || "",
        status: task.status || "",
        due: task.due || "",
        notes: task.notes || "",
        parent: task.parent || "",
        position: task.position || ""
    };
}

async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "tasks_list_tasklists": {
                var data = await tasksFetch("GET", "/users/@me/lists");
                var lists = (data.items || []).map(function(list) {
                    return {
                        id: list.id,
                        title: list.title,
                        updated: list.updated || ""
                    };
                });

                if (lists.length === 0) {
                    return { output: "No task lists found." };
                }

                return { output: JSON.stringify(lists, null, 2) };
            }

            case "tasks_list_tasks": {
                var listId = encodeURIComponent(args.tasklist_id);
                var maxResults = Math.min(args.max_results || 20, 100);
                var showCompleted = args.show_completed !== false;
                var showHidden = args.show_hidden === true;

                var path = "/lists/" + listId + "/tasks?" +
                    "maxResults=" + maxResults +
                    "&showCompleted=" + showCompleted +
                    "&showHidden=" + showHidden;

                var data = await tasksFetch("GET", path);
                var tasks = (data.items || []).map(formatTask);

                if (tasks.length === 0) {
                    return { output: "No tasks found in this list." };
                }

                return { output: JSON.stringify(tasks, null, 2) };
            }

            case "tasks_get_task": {
                var listId = encodeURIComponent(args.tasklist_id);
                var taskId = encodeURIComponent(args.task_id);

                var data = await tasksFetch("GET",
                    "/lists/" + listId + "/tasks/" + taskId);

                var result = {
                    id: data.id,
                    title: data.title || "",
                    status: data.status || "",
                    due: data.due || "",
                    notes: data.notes || "",
                    parent: data.parent || "",
                    position: data.position || "",
                    updated: data.updated || "",
                    completed: data.completed || "",
                    links: data.links || [],
                    selfLink: data.selfLink || ""
                };

                return { output: JSON.stringify(result, null, 2) };
            }

            case "tasks_create": {
                var listId = encodeURIComponent(args.tasklist_id);
                var task = { title: args.title };

                if (args.notes) task.notes = args.notes;
                if (args.due) task.due = args.due;

                var path = "/lists/" + listId + "/tasks";
                if (args.parent) {
                    path += "?parent=" + encodeURIComponent(args.parent);
                }

                var data = await tasksFetch("POST", path, JSON.stringify(task));

                return {
                    output: "Task created: " + data.title +
                        "\nID: " + data.id +
                        (data.due ? "\nDue: " + data.due : "") +
                        (data.parent ? "\nParent: " + data.parent : "")
                };
            }

            case "tasks_update": {
                var listId = encodeURIComponent(args.tasklist_id);
                var taskId = encodeURIComponent(args.task_id);

                var existing = await tasksFetch("GET",
                    "/lists/" + listId + "/tasks/" + taskId);

                if (args.title !== undefined) existing.title = args.title;
                if (args.notes !== undefined) existing.notes = args.notes;
                if (args.due !== undefined) existing.due = args.due;
                if (args.status !== undefined) existing.status = args.status;

                var data = await tasksFetch("PATCH",
                    "/lists/" + listId + "/tasks/" + taskId,
                    JSON.stringify(existing));

                return { output: "Task updated: " + data.title + " [" + data.status + "]" };
            }

            case "tasks_complete": {
                var listId = encodeURIComponent(args.tasklist_id);
                var taskId = encodeURIComponent(args.task_id);

                var data = await tasksFetch("PATCH",
                    "/lists/" + listId + "/tasks/" + taskId,
                    JSON.stringify({ status: "completed" }));

                return { output: "Task completed: " + data.title };
            }

            case "tasks_delete": {
                var listId = encodeURIComponent(args.tasklist_id);
                var taskId = encodeURIComponent(args.task_id);

                await tasksFetch("DELETE",
                    "/lists/" + listId + "/tasks/" + taskId);

                return { output: "Task deleted successfully." };
            }

            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        oneclaw.log.error("tasks error: " + e.message);
        return { error: e.message };
    }
}
