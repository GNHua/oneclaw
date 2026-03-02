/**
 * Google Tasks tool group for OneClawShadow.
 */

var TASKS_API = "https://www.googleapis.com/tasks/v1";

async function tasksFetch(method, path, body) {
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
    var resp = await fetch(TASKS_API + path, options);
    if (!resp.ok) {
        var errorText = await resp.text();
        throw new Error("Tasks API error (" + resp.status + "): " + errorText);
    }
    if (resp.status === 204) return { success: true };
    return await resp.json();
}

async function tasksListTasklists(params) {
    var data = await tasksFetch("GET", "/users/@me/lists");
    return { tasklists: data.items || [] };
}

async function tasksListTasks(params) {
    var listId = encodeURIComponent(params.tasklist_id);
    var query = "?";
    if (params.show_completed) query += "showCompleted=true&";
    if (params.max_results) query += "maxResults=" + params.max_results + "&";
    var data = await tasksFetch("GET", "/lists/" + listId + "/tasks" + query);
    return { tasks: data.items || [] };
}

async function tasksCreateTask(params) {
    var listId = encodeURIComponent(params.tasklist_id);
    var body = { title: params.title };
    if (params.notes) body.notes = params.notes;
    if (params.due) body.due = params.due;

    var path = "/lists/" + listId + "/tasks";
    if (params.parent) path += "?parent=" + encodeURIComponent(params.parent);

    return await tasksFetch("POST", path, body);
}

async function tasksUpdateTask(params) {
    var listId = encodeURIComponent(params.tasklist_id);
    var existing = await tasksFetch("GET", "/lists/" + listId + "/tasks/" + params.task_id);

    if (params.title !== undefined) existing.title = params.title;
    if (params.notes !== undefined) existing.notes = params.notes;
    if (params.status !== undefined) existing.status = params.status;
    if (params.due !== undefined) existing.due = params.due;

    return await tasksFetch("PUT", "/lists/" + listId + "/tasks/" + params.task_id, existing);
}

async function tasksCompleteTask(params) {
    var listId = encodeURIComponent(params.tasklist_id);
    var existing = await tasksFetch("GET", "/lists/" + listId + "/tasks/" + params.task_id);
    existing.status = "completed";
    existing.completed = new Date().toISOString();
    return await tasksFetch("PUT", "/lists/" + listId + "/tasks/" + params.task_id, existing);
}

async function tasksDeleteTask(params) {
    var listId = encodeURIComponent(params.tasklist_id);
    var token = await google.getAccessToken();
    var resp = await fetch(TASKS_API + "/lists/" + listId + "/tasks/" + params.task_id, {
        method: "DELETE",
        headers: { "Authorization": "Bearer " + token }
    });
    if (!resp.ok && resp.status !== 204) {
        var errorText = await resp.text();
        throw new Error("Tasks API error (" + resp.status + "): " + errorText);
    }
    return { success: true };
}

async function tasksCreateTasklist(params) {
    return await tasksFetch("POST", "/users/@me/lists", { title: params.title });
}

async function tasksGetTask(params) {
    var listId = encodeURIComponent(params.tasklist_id);
    return await tasksFetch("GET", "/lists/" + listId + "/tasks/" + params.task_id);
}
