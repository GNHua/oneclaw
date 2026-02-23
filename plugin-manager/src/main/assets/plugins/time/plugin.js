function formatTimezoneOffset(date) {
    var offsetMin = date.getTimezoneOffset();
    if (offsetMin === 0) return "+00:00";
    var sign = offsetMin <= 0 ? "+" : "-";
    var absMin = Math.abs(offsetMin);
    var h = String(Math.floor(absMin / 60)).padStart(2, "0");
    var m = String(absMin % 60).padStart(2, "0");
    return sign + h + ":" + m;
}

function execute(toolName, args) {
    var now = new Date();
    var tz = formatTimezoneOffset(now);
    var year = now.getFullYear();
    var month = String(now.getMonth() + 1).padStart(2, "0");
    var day = String(now.getDate()).padStart(2, "0");
    var h = String(now.getHours()).padStart(2, "0");
    var m = String(now.getMinutes()).padStart(2, "0");
    var s = String(now.getSeconds()).padStart(2, "0");
    switch (toolName) {
        case "get_current_datetime":
            return { output: year + "-" + month + "-" + day + " " + h + ":" + m + ":" + s + " (UTC" + tz + ")" };
        case "get_timestamp":
            var iso = year + "-" + month + "-" + day + "T" + h + ":" + m + ":" + s + tz;
            return { output: iso + " (" + String(now.getTime()) + ")" };
        default:
            return { error: "Unknown tool: " + toolName };
    }
}
