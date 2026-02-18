---
name: weather
description: Get current weather and forecast for any location
---

Get weather information using the Open-Meteo API (free, no API key required).

## How to fetch weather

1. First, geocode the location to get coordinates. Call `http_get` with:
   ```
   https://geocoding-api.open-meteo.com/v1/search?name={city_name}&count=1&language=en&format=json
   ```
   Extract `latitude`, `longitude`, and the resolved place name from the response.

2. Then fetch the weather. Call `http_get` with:
   ```
   https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=3
   ```

## Weather codes

| Code | Meaning |
|------|---------|
| 0 | Clear sky |
| 1-3 | Partly cloudy |
| 45, 48 | Fog |
| 51-55 | Drizzle |
| 61-65 | Rain |
| 71-75 | Snow |
| 80-82 | Rain showers |
| 95, 96, 99 | Thunderstorm |

## Response format

Present the weather concisely:
- Current: temperature (actual and feels-like), condition, humidity, wind
- Forecast: high/low and condition for each day
- Use the user's preferred units if known, otherwise default to Celsius

If the user does not specify a location, ask them.
