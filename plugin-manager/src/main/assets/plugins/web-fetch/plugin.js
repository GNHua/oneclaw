async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "http_get": {
                palmclaw.log.info("GET " + args.url);
                var body = await palmclaw.http.get(args.url);
                return { output: body };
            }
            case "http_post": {
                palmclaw.log.info("POST " + args.url);
                var body = await palmclaw.http.post(args.url, args.body);
                return { output: body };
            }
            case "http_request": {
                var method = args.method || "GET";
                palmclaw.log.info(method + " " + args.url);
                var raw = await palmclaw.http.fetch(
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
        palmclaw.log.error("web-fetch error: " + e.message);
        return { error: e.message };
    }
}
