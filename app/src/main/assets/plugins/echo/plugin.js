function execute(toolName, args) {
    if (toolName === "echo") {
        return { output: args.message };
    }
    return { error: "Unknown tool: " + toolName };
}
