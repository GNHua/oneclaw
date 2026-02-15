function execute(toolName, args) {
    switch (toolName) {
        case "add":
            return { output: String(args.a + args.b) };
        case "subtract":
            return { output: String(args.a - args.b) };
        case "multiply":
            return { output: String(args.a * args.b) };
        case "divide":
            if (args.b === 0) return { error: "Division by zero" };
            return { output: String(args.a / args.b) };
        default:
            return { error: "Unknown tool: " + toolName };
    }
}
