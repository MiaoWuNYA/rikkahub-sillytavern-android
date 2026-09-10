// 天气查询插件 - 使用 wttr.in API 获取真实天气数据
// fetch 由橘瓣插件沙箱注入，是同步函数（不需要 await）

function get_weather(params) {
  var city = params.city || "Beijing";
  var url = "https://wttr.in/" + encodeURIComponent(city) + "?format=j1";
  
  console.log("Fetching weather for: " + city);
  
  var response = fetch(url);
  
  if (!response.ok) {
    return { success: false, error: "Failed to fetch weather data" };
  }
  
  var data = JSON.parse(response.body);
  var current = data.current_condition[0];
  var weatherDesc = current.weatherDesc[0].value;
  var tempC = current.temp_C;
  var humidity = current.humidity;
  var windSpeed = current.windspeedKmph;
  var feelsLike = current.FeelsLikeC;
  var visibility = current.visibility;
  var pressure = current.pressure;
  var uvIndex = current.uvIndex;
  var cloudCover = current.cloudcover;
  var precip = current.precipMM;
  var windDir = current.winddir16Point;
  
  return {
    success: true,
    city: city,
    description: weatherDesc,
    temperature: tempC + "°C",
    feels_like: feelsLike + "°C",
    humidity: humidity + "%",
    wind_speed: windSpeed + " km/h",
    wind_direction: windDir,
    visibility: visibility + " km",
    pressure: pressure + " hPa",
    uv_index: uvIndex,
    cloud_cover: cloudCover + "%",
    precipitation: precip + " mm"
  };
}

function get_weather_brief(params) {
  var city = params.city || "Beijing";
  var url = "https://wttr.in/" + encodeURIComponent(city) + "?format=%C+%t+%h+%w";
  
  var response = fetch(url);
  
  if (!response.ok) {
    return { success: false, error: "Failed to fetch weather" };
  }
  
  return {
    success: true,
    city: city,
    brief: response.body.trim()
  };
}

function get_weather_forecast(params) {
  var city = params.city || "Beijing";
  var days = params.days || 3;
  if (days > 3) days = 3;
  if (days < 1) days = 1;
  
  var url = "https://wttr.in/" + encodeURIComponent(city) + "?format=j1";
  
  console.log("Fetching weather forecast for: " + city + ", days: " + days);
  
  var response = fetch(url);
  
  if (!response.ok) {
    return { success: false, error: "Failed to fetch weather forecast" };
  }
  
  var data = JSON.parse(response.body);
  var forecasts = [];
  
  var weatherList = data.weather || [];
  for (var i = 0; i < days && i < weatherList.length; i++) {
    var day = weatherList[i];
    var astro = day.astronomy && day.astronomy.length > 0 ? day.astronomy[0] : null;
    
    var hourlyTemps = [];
    var hourlyList = day.hourly || [];
    for (var h = 0; h < hourlyList.length; h++) {
      hourlyTemps.push({
        time: hourlyList[h].time,
        temp: hourlyList[h].tempC + "°C",
        description: hourlyList[h].weatherDesc && hourlyList[h].weatherDesc[0] ? hourlyList[h].weatherDesc[0].value : "",
        chance_of_rain: hourlyList[h].chanceofrain + "%",
        humidity: hourlyList[h].humidity + "%",
        wind_speed: hourlyList[h].windspeedKmph + " km/h"
      });
    }
    
    forecasts.push({
      date: day.date,
      max_temp: day.maxtempC + "°C",
      min_temp: day.mintempC + "°C",
      avg_temp: day.avgtempC + "°C",
      total_snow: day.totalSnow_cm + " cm",
      sun_hour: day.sunHour,
      uv_index: day.uvIndex,
      chance_of_rain: day.hourly && day.hourly.length > 0 ? day.hourly[4].chanceofrain + "%" : "N/A",
      chance_of_snow: day.hourly && day.hourly.length > 0 ? day.hourly[4].chanceofsnow + "%" : "N/A",
      description: day.hourly && day.hourly.length > 4 && day.hourly[4].weatherDesc && day.hourly[4].weatherDesc[0] ? day.hourly[4].weatherDesc[0].value : "",
      astronomy: astro ? {
        sunrise: astro.sunrise,
        sunset: astro.sunset,
        moonrise: astro.moonrise,
        moonset: astro.moonset,
        moon_phase: astro.moon_phase
      } : null,
      hourly: hourlyTemps
    });
  }
  
  return {
    success: true,
    city: city,
    forecast_days: days,
    forecasts: forecasts
  };
}

exports.get_weather = get_weather;
exports.get_weather_brief = get_weather_brief;
exports.get_weather_forecast = get_weather_forecast;