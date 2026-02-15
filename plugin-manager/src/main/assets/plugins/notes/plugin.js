var notes = [];

function execute(toolName, args) {
    switch (toolName) {
        case "save_note":
            notes.push(args.note);
            return { output: "Note saved. Total notes: " + notes.length };
        case "get_notes":
            if (notes.length === 0) return { output: "No notes saved." };
            return { output: notes.map(function(n, i) { return (i + 1) + ". " + n; }).join("\n") };
        case "clear_notes":
            var count = notes.length;
            notes = [];
            return { output: "Cleared " + count + " notes." };
        default:
            return { error: "Unknown tool: " + toolName };
    }
}
