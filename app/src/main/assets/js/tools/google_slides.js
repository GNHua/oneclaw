/**
 * Google Slides tool group for OneClawShadow.
 */

var SLIDES_API = "https://slides.googleapis.com/v1";

async function slidesFetch(method, path, body) {
    var token = await google.getAccessToken();
    var options = {
        method: method,
        headers: {
            "Authorization": "Bearer " + token,
            "Content-Type": "application/json"
        }
    };
    if (body) {
        options.body = JSON.stringify(body);
    }
    var resp = await fetch(SLIDES_API + path, options);
    if (!resp.ok) {
        var errorText = await resp.text();
        throw new Error("Slides API error (" + resp.status + "): " + errorText);
    }
    return await resp.json();
}

async function slidesGet(params) {
    return await slidesFetch("GET", "/presentations/" + params.presentation_id);
}

async function slidesCreate(params) {
    return await slidesFetch("POST", "/presentations", { title: params.title });
}

async function slidesAddSlide(params) {
    var request = { createSlide: {} };
    if (params.layout) {
        request.createSlide.slideLayoutReference = { predefinedLayout: params.layout };
    }
    if (params.insertion_index !== undefined) {
        request.createSlide.insertionIndex = params.insertion_index;
    }
    return await slidesFetch("POST", "/presentations/" + params.presentation_id + ":batchUpdate", {
        requests: [request]
    });
}

async function slidesGetText(params) {
    var presentation = await slidesFetch("GET", "/presentations/" + params.presentation_id);
    var result = {
        title: presentation.title,
        presentationId: presentation.presentationId,
        slides: []
    };

    for (var i = 0; i < (presentation.slides || []).length; i++) {
        var slide = presentation.slides[i];
        var slideText = { objectId: slide.objectId, texts: [] };

        for (var j = 0; j < (slide.pageElements || []).length; j++) {
            var element = slide.pageElements[j];
            if (element.shape && element.shape.text) {
                var text = extractTextFromTextContent(element.shape.text);
                if (text) slideText.texts.push(text);
            }
        }
        result.slides.push(slideText);
    }
    return result;
}

async function slidesBatchUpdate(params) {
    return await slidesFetch("POST", "/presentations/" + params.presentation_id + ":batchUpdate", {
        requests: params.requests
    });
}

async function slidesDeleteSlide(params) {
    return await slidesFetch("POST", "/presentations/" + params.presentation_id + ":batchUpdate", {
        requests: [{
            deleteObject: { objectId: params.slide_id }
        }]
    });
}

function extractTextFromTextContent(textContent) {
    if (!textContent || !textContent.textElements) return "";
    var text = "";
    for (var i = 0; i < textContent.textElements.length; i++) {
        var te = textContent.textElements[i];
        if (te.textRun && te.textRun.content) {
            text += te.textRun.content;
        }
    }
    return text.trim();
}
