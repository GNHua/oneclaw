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
            var ty = now.getFullYear();
            var tm = String(now.getMonth() + 1).padStart(2, "0");
            var td = String(now.getDate()).padStart(2, "0");
            var th = String(now.getHours()).padStart(2, "0");
            var tmi = String(now.getMinutes()).padStart(2, "0");
            var ts = String(now.getSeconds()).padStart(2, "0");
            var iso = ty + "-" + tm + "-" + td + "T" + th + ":" + tmi + ":" + ts;
            return { output: iso + " (" + String(now.getTime()) + ")" };
        default:
            return { error: "Unknown tool: " + toolName };
    }
}
