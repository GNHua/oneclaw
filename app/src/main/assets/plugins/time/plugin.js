function execute(toolName, args) {
    var now = new Date();
    switch (toolName) {
        case "get_current_time":
            var h = String(now.getHours()).padStart(2, "0");
            var m = String(now.getMinutes()).padStart(2, "0");
            var s = String(now.getSeconds()).padStart(2, "0");
            return { output: h + ":" + m + ":" + s };
        case "get_current_date":
            var year = now.getFullYear();
            var month = String(now.getMonth() + 1).padStart(2, "0");
            var day = String(now.getDate()).padStart(2, "0");
            return { output: year + "-" + month + "-" + day };
        case "get_timestamp":
            return { output: String(now.getTime()) };
        default:
            return { error: "Unknown tool: " + toolName };
    }
}
