var NOTES_DIR = "notes";

function ensureDir() {
    if (!palmclaw.fs.exists(NOTES_DIR)) {
        palmclaw.fs.writeFile(NOTES_DIR + "/.keep", "");
    }
}

function notePath(filename) {
    return NOTES_DIR + "/" + filename;
}

function execute(toolName, args) {
    try {
        ensureDir();

        switch (toolName) {
            case "ws_save_note": {
                var path = notePath(args.filename);
                palmclaw.fs.writeFile(path, args.content);
                palmclaw.log.info("Saved note: " + args.filename);
                return { output: "Saved " + args.filename };
            }
            case "ws_read_note": {
                var path = notePath(args.filename);
                if (!palmclaw.fs.exists(path)) {
                    return { error: "Note not found: " + args.filename };
                }
                var content = palmclaw.fs.readFile(path);
                return { output: content };
            }
            case "ws_append_note": {
                var path = notePath(args.filename);
                palmclaw.fs.appendFile(path, args.content);
                return { output: "Appended to " + args.filename };
            }
            case "ws_list_notes": {
                var listing = palmclaw.fs.listFiles(NOTES_DIR);
                if (!listing || listing.length === 0) {
                    return { output: "No notes found." };
                }
                return { output: listing };
            }
            case "ws_delete_note": {
                var path = notePath(args.filename);
                if (!palmclaw.fs.exists(path)) {
                    return { error: "Note not found: " + args.filename };
                }
                palmclaw.fs.deleteFile(path);
                palmclaw.log.info("Deleted note: " + args.filename);
                return { output: "Deleted " + args.filename };
            }
            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        palmclaw.log.error("workspace-notes error: " + e.message);
        return { error: e.message };
    }
}
