var NOTES_DIR = "notes";

function ensureDir() {
    if (!oneclaw.fs.exists(NOTES_DIR)) {
        oneclaw.fs.writeFile(NOTES_DIR + "/.keep", "");
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
                oneclaw.fs.writeFile(path, args.content);
                oneclaw.log.info("Saved note: " + args.filename);
                return { output: "Saved " + args.filename };
            }
            case "ws_read_note": {
                var path = notePath(args.filename);
                if (!oneclaw.fs.exists(path)) {
                    return { error: "Note not found: " + args.filename };
                }
                var content = oneclaw.fs.readFile(path);
                return { output: content };
            }
            case "ws_append_note": {
                var path = notePath(args.filename);
                oneclaw.fs.appendFile(path, args.content);
                return { output: "Appended to " + args.filename };
            }
            case "ws_list_notes": {
                var listing = oneclaw.fs.listFiles(NOTES_DIR);
                if (!listing || listing.length === 0) {
                    return { output: "No notes found." };
                }
                return { output: listing };
            }
            case "ws_delete_note": {
                var path = notePath(args.filename);
                if (!oneclaw.fs.exists(path)) {
                    return { error: "Note not found: " + args.filename };
                }
                oneclaw.fs.deleteFile(path);
                oneclaw.log.info("Deleted note: " + args.filename);
                return { output: "Deleted " + args.filename };
            }
            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        oneclaw.log.error("workspace-notes error: " + e.message);
        return { error: e.message };
    }
}
