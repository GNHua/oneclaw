async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "generate_image":
                return await generateImage(args);
            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        palmclaw.log.error("image-gen error: " + e.message);
        return { error: e.message };
    }
}

async function generateImage(args) {
    var apiKey = await palmclaw.credentials.getProviderKey("OpenAI");
    if (!apiKey) {
        return { error: "OpenAI API key not configured. Please set it in Settings > API Keys." };
    }

    var model = args.model || "dall-e-3";
    var size = args.size || "1024x1024";
    var quality = args.quality || "standard";
    var style = args.style || "vivid";

    var body = {
        model: model,
        prompt: args.prompt,
        n: 1,
        size: size,
        quality: quality,
        response_format: "url"
    };

    if (model === "dall-e-3") {
        body.style = style;
    }

    palmclaw.log.info("Generating image with " + model + ": " + args.prompt.substring(0, 80));

    var raw = await palmclaw.http.fetch(
        "POST",
        "https://api.openai.com/v1/images/generations",
        JSON.stringify(body),
        "application/json",
        { "Authorization": "Bearer " + apiKey }
    );

    var resp = JSON.parse(raw);
    if (resp.status !== 200) {
        var errorBody = JSON.parse(resp.body);
        var errorMsg = (errorBody.error && errorBody.error.message) || resp.body;
        return { error: "OpenAI API error (HTTP " + resp.status + "): " + errorMsg };
    }

    var data = JSON.parse(resp.body);
    var imageUrl = data.data[0].url;
    var revisedPrompt = data.data[0].revised_prompt || args.prompt;

    var timestamp = Date.now();
    var safeName = args.prompt.substring(0, 40).replace(/[^a-zA-Z0-9]/g, "_").replace(/_+/g, "_");
    var filename = "images/" + timestamp + "-" + safeName + ".png";

    var dlResult = await palmclaw.http.downloadToFile(imageUrl, filename);
    var dl = JSON.parse(dlResult);

    var output = "Image generated and saved to: " + dl.path + "\n";
    output += "Model: " + model + "\n";
    output += "Size: " + size + "\n";
    output += "Quality: " + quality + "\n";
    output += "File size: " + dl.size + " bytes\n";
    if (revisedPrompt !== args.prompt) {
        output += "Revised prompt: " + revisedPrompt;
    }

    return { output: output, imagePaths: [filename] };
}
