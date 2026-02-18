async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "http_get": {
                oneclaw.log.info("GET " + args.url);
                var body = await oneclaw.http.get(args.url);
                return { output: body };
            }
            case "http_post": {
                oneclaw.log.info("POST " + args.url);
                var body = await oneclaw.http.post(args.url, args.body);
                return { output: body };
            }
            case "http_request": {
                var method = args.method || "GET";
                oneclaw.log.info(method + " " + args.url);
                var raw = await oneclaw.http.fetch(
                    method,
                    args.url,
                    args.body || null,
                    args.content_type || "application/json",
                    args.headers || null
                );
                var resp = JSON.parse(raw);
                var result = "Status: " + resp.status + "\n";
                result += "Body:\n" + resp.body;
                return { output: result };
            }
            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        oneclaw.log.error("web-fetch error: " + e.message);
        return { error: e.message };
    }
}
