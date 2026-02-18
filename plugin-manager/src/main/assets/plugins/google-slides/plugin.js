var SLIDES_API = "https://slides.googleapis.com/v1";

async function getToken() {
    if (typeof oneclaw.google === "undefined") {
        throw new Error("Google auth not available. Connect your Google account in Settings.");
    }
    var token = await oneclaw.google.getAccessToken();
    if (!token) {
        throw new Error("Not signed in to Google. Connect your Google account in Settings.");
    }
    return token;
}

async function slidesFetch(method, path, body) {
    var token = await getToken();
    var headers = { "Authorization": "Bearer " + token };
    var raw = await oneclaw.http.fetch(
        method,
        SLIDES_API + path,
        body || null,
        "application/json",
        headers
    );
    var resp = JSON.parse(raw);
    if (resp.status >= 400) {
        throw new Error("Slides API error (HTTP " + resp.status + "): " + resp.body);
    }
    if (!resp.body || resp.body.length === 0) return {};
    return JSON.parse(resp.body);
}

function extractSlideText(slide) {
    var text = "";
    var elements = slide.pageElements || [];
    for (var i = 0; i < elements.length; i++) {
        var el = elements[i];
        if (el.shape && el.shape.text && el.shape.text.textElements) {
            var textEls = el.shape.text.textElements;
            for (var j = 0; j < textEls.length; j++) {
                if (textEls[j].textRun && textEls[j].textRun.content) {
                    text += textEls[j].textRun.content;
                }
            }
        }
    }
    return text;
}

async function execute(toolName, args) {
    try {
        switch (toolName) {
            case "slides_get": {
                var data = await slidesFetch("GET",
                    "/presentations/" + encodeURIComponent(args.presentation_id));

                var slides = data.slides || [];
                var slideIds = [];
                for (var i = 0; i < slides.length; i++) {
                    slideIds.push(slides[i].objectId);
                }

                var result = {
                    presentationId: data.presentationId,
                    title: data.title || "(untitled)",
                    slidesCount: slides.length,
                    slideIds: slideIds
                };

                return { output: JSON.stringify(result, null, 2) };
            }

            case "slides_create": {
                var data = await slidesFetch("POST", "/presentations",
                    JSON.stringify({ title: args.title }));

                return {
                    output: "Presentation created: " + data.title +
                        "\nID: " + data.presentationId +
                        "\nURL: https://docs.google.com/presentation/d/" + data.presentationId + "/edit"
                };
            }

            case "slides_list_slides": {
                var data = await slidesFetch("GET",
                    "/presentations/" + encodeURIComponent(args.presentation_id));

                var slides = data.slides || [];
                var result = [];
                for (var i = 0; i < slides.length; i++) {
                    result.push({
                        index: i,
                        objectId: slides[i].objectId,
                        pageType: slides[i].pageType || "SLIDE"
                    });
                }

                if (result.length === 0) {
                    return { output: "Presentation has no slides." };
                }

                return { output: JSON.stringify(result, null, 2) };
            }

            case "slides_get_slide_text": {
                var data = await slidesFetch("GET",
                    "/presentations/" + encodeURIComponent(args.presentation_id));

                var slides = data.slides || [];
                var index = args.slide_index;

                if (index < 0 || index >= slides.length) {
                    return { error: "Slide index " + index + " out of range. Presentation has " + slides.length + " slide(s) (0-" + (slides.length - 1) + ")." };
                }

                var text = extractSlideText(slides[index]);

                if (!text || text.trim().length === 0) {
                    return { output: "Slide " + index + " contains no text content." };
                }

                return { output: text };
            }

            case "slides_add_slide": {
                var createSlideRequest = {
                    slideLayoutReference: {
                        predefinedLayout: args.layout || "BLANK"
                    }
                };

                if (args.insert_at !== undefined && args.insert_at !== null) {
                    createSlideRequest.insertionIndex = args.insert_at;
                }

                var data = await slidesFetch("POST",
                    "/presentations/" + encodeURIComponent(args.presentation_id) + ":batchUpdate",
                    JSON.stringify({
                        requests: [{ createSlide: createSlideRequest }]
                    }));

                var replies = data.replies || [];
                var newSlideId = "";
                if (replies.length > 0 && replies[0].createSlide) {
                    newSlideId = replies[0].createSlide.objectId;
                }

                return {
                    output: "Slide added" +
                        (args.insert_at !== undefined ? " at index " + args.insert_at : "") +
                        (newSlideId ? ". New slide ID: " + newSlideId : "") +
                        ". Layout: " + (args.layout || "BLANK")
                };
            }

            case "slides_delete_slide": {
                await slidesFetch("POST",
                    "/presentations/" + encodeURIComponent(args.presentation_id) + ":batchUpdate",
                    JSON.stringify({
                        requests: [{ deleteObject: { objectId: args.slide_id } }]
                    }));

                return { output: "Slide deleted: " + args.slide_id };
            }

            default:
                return { error: "Unknown tool: " + toolName };
        }
    } catch (e) {
        oneclaw.log.error("slides error: " + e.message);
        return { error: e.message };
    }
}
