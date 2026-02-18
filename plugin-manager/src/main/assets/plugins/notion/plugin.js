var NOTION_API = "https://api.notion.com/v1";
var NOTION_VERSION = "2022-06-28";

async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "notion_search":
                return await notionSearch(args);
            case "notion_get_page":
                return await notionGetPage(args);
            case "notion_get_page_content":
                return await notionGetPageContent(args);
            case "notion_create_page":
                return await notionCreatePage(args);
            case "notion_update_page":
                return await notionUpdatePage(args);
            case "notion_query_database":
                return await notionQueryDatabase(args);
            case "notion_add_blocks":
                return await notionAddBlocks(args);
            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        palmclaw.log.error("notion error: " + e.message);
        return { error: e.message };
    }
}

async function getApiKey() {
    var apiKey = await palmclaw.credentials.get("api_key");
    if (!apiKey) {
        throw new Error("Notion API key not configured. Please set it in Settings > Plugins > Notion.");
    }
    return apiKey;
}

async function notionFetch(method, path, body) {
    var apiKey = await getApiKey();
    var headers = {
        "Authorization": "Bearer " + apiKey,
        "Notion-Version": NOTION_VERSION
    };

    var raw = await palmclaw.http.fetch(
        method,
        NOTION_API + path,
        body ? JSON.stringify(body) : "",
        "application/json",
        headers
    );

    var resp = JSON.parse(raw);
    if (resp.status >= 400) {
        var errorBody;
        try { errorBody = JSON.parse(resp.body); } catch (e) { errorBody = {}; }
        var msg = (errorBody.message) || resp.body || "HTTP " + resp.status;
        throw new Error("Notion API error (HTTP " + resp.status + "): " + msg);
    }

    return JSON.parse(resp.body);
}

// -- Search --

async function notionSearch(args) {
    if (!args.query) {
        return { error: "Missing required field: query" };
    }

    var body = {
        query: args.query,
        page_size: Math.min(args.max_results || 10, 100)
    };

    if (args.filter === "page" || args.filter === "database") {
        body.filter = { property: "object", value: args.filter };
    }

    var data = await notionFetch("POST", "/search", body);
    var results = data.results || [];

    if (results.length === 0) {
        return { output: "No results found for '" + args.query + "'." };
    }

    var lines = [];
    lines.push("Found " + results.length + " result(s):\n");

    for (var i = 0; i < results.length; i++) {
        var item = results[i];
        var title = extractTitle(item);
        var type = item.object;
        var id = item.id;
        lines.push((i + 1) + ". [" + type + "] " + title);
        lines.push("   ID: " + id);
        if (item.url) {
            lines.push("   URL: " + item.url);
        }
    }

    return { output: lines.join("\n") };
}

// -- Get Page --

async function notionGetPage(args) {
    if (!args.page_id) {
        return { error: "Missing required field: page_id" };
    }

    var page = await notionFetch("GET", "/pages/" + args.page_id);

    var lines = [];
    var title = extractTitle(page);
    lines.push("Title: " + title);
    lines.push("ID: " + page.id);
    if (page.url) {
        lines.push("URL: " + page.url);
    }
    lines.push("Created: " + page.created_time);
    lines.push("Last edited: " + page.last_edited_time);
    lines.push("Archived: " + (page.archived || false));

    if (page.properties) {
        lines.push("\nProperties:");
        var keys = Object.keys(page.properties);
        for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            var prop = page.properties[key];
            var val = extractPropertyValue(prop);
            if (val) {
                lines.push("  " + key + ": " + val);
            }
        }
    }

    return { output: lines.join("\n") };
}

// -- Get Page Content --

async function notionGetPageContent(args) {
    if (!args.page_id) {
        return { error: "Missing required field: page_id" };
    }

    var maxBlocks = args.max_blocks || 100;
    var blocks = [];
    var cursor = undefined;

    while (blocks.length < maxBlocks) {
        var path = "/blocks/" + args.page_id + "/children?page_size=" + Math.min(maxBlocks - blocks.length, 100);
        if (cursor) {
            path += "&start_cursor=" + cursor;
        }

        var data = await notionFetch("GET", path);
        var results = data.results || [];
        blocks = blocks.concat(results);

        if (!data.has_more || results.length === 0) {
            break;
        }
        cursor = data.next_cursor;
    }

    if (blocks.length === 0) {
        return { output: "(Page is empty)" };
    }

    var lines = [];
    for (var i = 0; i < blocks.length; i++) {
        var text = blockToText(blocks[i]);
        if (text !== null) {
            lines.push(text);
        }
    }

    return { output: lines.join("\n") };
}

// -- Create Page --

async function notionCreatePage(args) {
    if (!args.parent_id) {
        return { error: "Missing required field: parent_id" };
    }
    if (!args.title) {
        return { error: "Missing required field: title" };
    }

    var body = {};

    // Detect if parent is a database (32-char hex) or page
    // Try as database first; if that fails, treat as page
    var parentId = args.parent_id.replace(/-/g, "");
    body.parent = { database_id: args.parent_id };
    body.properties = {
        "Name": {
            title: [{ text: { content: args.title } }]
        }
    };

    // Merge additional properties
    if (args.properties && typeof args.properties === "object") {
        var keys = Object.keys(args.properties);
        for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            var val = args.properties[key];
            // Simple string values become rich_text
            if (typeof val === "string") {
                body.properties[key] = {
                    rich_text: [{ text: { content: val } }]
                };
            } else {
                body.properties[key] = val;
            }
        }
    }

    // Add content blocks
    if (args.content) {
        body.children = textToBlocks(args.content);
    }

    var page;
    try {
        page = await notionFetch("POST", "/pages", body);
    } catch (e) {
        // If database parent failed, try as page parent
        if (e.message && e.message.indexOf("validation_error") !== -1) {
            body.parent = { page_id: args.parent_id };
            body.properties = {
                title: [{ text: { content: args.title } }]
            };
            page = await notionFetch("POST", "/pages", body);
        } else {
            throw e;
        }
    }

    var title = extractTitle(page);
    return { output: "Page created: " + title + "\nID: " + page.id + "\nURL: " + (page.url || "") };
}

// -- Update Page --

async function notionUpdatePage(args) {
    if (!args.page_id) {
        return { error: "Missing required field: page_id" };
    }

    var body = {};

    if (args.properties && typeof args.properties === "object") {
        body.properties = {};
        var keys = Object.keys(args.properties);
        for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            var val = args.properties[key];
            if (typeof val === "string") {
                // Check if it looks like a title property
                if (key === "Name" || key === "title" || key === "Title") {
                    body.properties[key] = {
                        title: [{ text: { content: val } }]
                    };
                } else {
                    body.properties[key] = {
                        rich_text: [{ text: { content: val } }]
                    };
                }
            } else {
                body.properties[key] = val;
            }
        }
    }

    if (args.archived !== undefined) {
        body.archived = args.archived;
    }

    var page = await notionFetch("PATCH", "/pages/" + args.page_id, body);
    var title = extractTitle(page);
    return { output: "Page updated: " + title + "\nID: " + page.id };
}

// -- Query Database --

async function notionQueryDatabase(args) {
    if (!args.database_id) {
        return { error: "Missing required field: database_id" };
    }

    var body = {
        page_size: Math.min(args.max_results || 20, 100)
    };

    if (args.filter) {
        body.filter = args.filter;
    }
    if (args.sorts) {
        body.sorts = args.sorts;
    }

    var data = await notionFetch("POST", "/databases/" + args.database_id + "/query", body);
    var results = data.results || [];

    if (results.length === 0) {
        return { output: "No results found in database." };
    }

    var lines = [];
    lines.push("Found " + results.length + " page(s):\n");

    for (var i = 0; i < results.length; i++) {
        var page = results[i];
        var title = extractTitle(page);
        lines.push((i + 1) + ". " + title);
        lines.push("   ID: " + page.id);

        // Show key properties
        if (page.properties) {
            var propKeys = Object.keys(page.properties);
            for (var j = 0; j < propKeys.length; j++) {
                var propKey = propKeys[j];
                var prop = page.properties[propKey];
                if (prop.type === "title") continue; // Already shown
                var val = extractPropertyValue(prop);
                if (val) {
                    lines.push("   " + propKey + ": " + val);
                }
            }
        }
    }

    return { output: lines.join("\n") };
}

// -- Add Blocks --

async function notionAddBlocks(args) {
    if (!args.page_id) {
        return { error: "Missing required field: page_id" };
    }
    if (!args.content) {
        return { error: "Missing required field: content" };
    }

    var children = textToBlocks(args.content);
    var body = { children: children };
    await notionFetch("PATCH", "/blocks/" + args.page_id + "/children", body);

    return { output: "Added " + children.length + " block(s) to page " + args.page_id + "." };
}

// -- Helpers --

function extractTitle(item) {
    if (item.properties) {
        var keys = Object.keys(item.properties);
        for (var i = 0; i < keys.length; i++) {
            var prop = item.properties[keys[i]];
            if (prop.type === "title" && prop.title && prop.title.length > 0) {
                return prop.title.map(function(t) { return t.plain_text || ""; }).join("");
            }
        }
    }
    // Fallback for child pages
    if (item.child_page && item.child_page.title) {
        return item.child_page.title;
    }
    return "(Untitled)";
}

function extractPropertyValue(prop) {
    if (!prop || !prop.type) return "";

    switch (prop.type) {
        case "title":
            if (prop.title && prop.title.length > 0) {
                return prop.title.map(function(t) { return t.plain_text || ""; }).join("");
            }
            return "";
        case "rich_text":
            if (prop.rich_text && prop.rich_text.length > 0) {
                return prop.rich_text.map(function(t) { return t.plain_text || ""; }).join("");
            }
            return "";
        case "number":
            return prop.number !== null ? String(prop.number) : "";
        case "select":
            return prop.select ? prop.select.name : "";
        case "multi_select":
            if (prop.multi_select && prop.multi_select.length > 0) {
                return prop.multi_select.map(function(s) { return s.name; }).join(", ");
            }
            return "";
        case "date":
            if (prop.date) {
                var d = prop.date.start || "";
                if (prop.date.end) d += " -> " + prop.date.end;
                return d;
            }
            return "";
        case "checkbox":
            return prop.checkbox ? "Yes" : "No";
        case "url":
            return prop.url || "";
        case "email":
            return prop.email || "";
        case "phone_number":
            return prop.phone_number || "";
        case "status":
            return prop.status ? prop.status.name : "";
        case "created_time":
            return prop.created_time || "";
        case "last_edited_time":
            return prop.last_edited_time || "";
        case "people":
            if (prop.people && prop.people.length > 0) {
                return prop.people.map(function(p) { return p.name || p.id; }).join(", ");
            }
            return "";
        case "relation":
            if (prop.relation && prop.relation.length > 0) {
                return prop.relation.map(function(r) { return r.id; }).join(", ");
            }
            return "";
        case "formula":
            if (prop.formula) {
                return String(prop.formula.string || prop.formula.number || prop.formula.boolean || prop.formula.date || "");
            }
            return "";
        case "rollup":
            if (prop.rollup) {
                return String(prop.rollup.number || prop.rollup.string || JSON.stringify(prop.rollup.array || []));
            }
            return "";
        default:
            return "";
    }
}

function blockToText(block) {
    if (!block || !block.type) return null;
    var type = block.type;

    switch (type) {
        case "paragraph":
            return richTextToString(block.paragraph.rich_text);
        case "heading_1":
            return "# " + richTextToString(block.heading_1.rich_text);
        case "heading_2":
            return "## " + richTextToString(block.heading_2.rich_text);
        case "heading_3":
            return "### " + richTextToString(block.heading_3.rich_text);
        case "bulleted_list_item":
            return "- " + richTextToString(block.bulleted_list_item.rich_text);
        case "numbered_list_item":
            return "1. " + richTextToString(block.numbered_list_item.rich_text);
        case "to_do":
            var checked = block.to_do.checked ? "[x]" : "[ ]";
            return "- " + checked + " " + richTextToString(block.to_do.rich_text);
        case "toggle":
            return "> " + richTextToString(block.toggle.rich_text);
        case "code":
            var lang = block.code.language || "";
            var code = richTextToString(block.code.rich_text);
            return "```" + lang + "\n" + code + "\n```";
        case "quote":
            return "> " + richTextToString(block.quote.rich_text);
        case "callout":
            var icon = "";
            if (block.callout.icon && block.callout.icon.emoji) {
                icon = block.callout.icon.emoji + " ";
            }
            return icon + richTextToString(block.callout.rich_text);
        case "divider":
            return "---";
        case "bookmark":
            return "[Bookmark] " + (block.bookmark.url || "");
        case "image":
            var imgUrl = "";
            if (block.image.file) imgUrl = block.image.file.url;
            else if (block.image.external) imgUrl = block.image.external.url;
            return "[Image] " + imgUrl;
        case "equation":
            return block.equation.expression || "";
        case "table_of_contents":
            return "[Table of Contents]";
        case "child_page":
            return "[Child Page: " + (block.child_page.title || "") + "]";
        case "child_database":
            return "[Child Database: " + (block.child_database.title || "") + "]";
        default:
            return "[" + type + " block]";
    }
}

function richTextToString(richText) {
    if (!richText || richText.length === 0) return "";
    return richText.map(function(t) { return t.plain_text || ""; }).join("");
}

function textToBlocks(text) {
    var lines = text.split("\n");
    var blocks = [];

    for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        var block;

        if (line.startsWith("### ")) {
            block = {
                object: "block",
                type: "heading_3",
                heading_3: { rich_text: [{ type: "text", text: { content: line.substring(4) } }] }
            };
        } else if (line.startsWith("## ")) {
            block = {
                object: "block",
                type: "heading_2",
                heading_2: { rich_text: [{ type: "text", text: { content: line.substring(3) } }] }
            };
        } else if (line.startsWith("# ")) {
            block = {
                object: "block",
                type: "heading_1",
                heading_1: { rich_text: [{ type: "text", text: { content: line.substring(2) } }] }
            };
        } else if (line.match(/^- \[[ x]\] /)) {
            var checked = line.charAt(3) === "x";
            block = {
                object: "block",
                type: "to_do",
                to_do: {
                    rich_text: [{ type: "text", text: { content: line.substring(6) } }],
                    checked: checked
                }
            };
        } else if (line.startsWith("- ")) {
            block = {
                object: "block",
                type: "bulleted_list_item",
                bulleted_list_item: { rich_text: [{ type: "text", text: { content: line.substring(2) } }] }
            };
        } else if (line.match(/^\d+\. /)) {
            var content = line.replace(/^\d+\.\s*/, "");
            block = {
                object: "block",
                type: "numbered_list_item",
                numbered_list_item: { rich_text: [{ type: "text", text: { content: content } }] }
            };
        } else if (line.startsWith("> ")) {
            block = {
                object: "block",
                type: "quote",
                quote: { rich_text: [{ type: "text", text: { content: line.substring(2) } }] }
            };
        } else if (line === "---") {
            block = {
                object: "block",
                type: "divider",
                divider: {}
            };
        } else {
            block = {
                object: "block",
                type: "paragraph",
                paragraph: { rich_text: [{ type: "text", text: { content: line } }] }
            };
        }

        blocks.push(block);
    }

    return blocks;
}
