package main

import (
	"context"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"

	"cloud.google.com/go/firestore"
	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/auth"
	"github.com/gin-gonic/gin"
	"github.com/mmcloughlin/geohash"
	"google.golang.org/api/iterator"
	"google.golang.org/api/option"
)

// GPX Models for XML parsing
type GPX struct {
	XMLName xml.Name `xml:"gpx"`
	Tracks  []Track  `xml:"trk"`
}

type RouteWeatherRequest struct {
	StartTimeUnix int64 `json:"start_time_unix"`
	Waypoints     []struct {
		Lat           float64 `json:"lat"`
		Lon           float64 `json:"lon"`
		TimeFromStart int     `json:"time_from_start_seconds"`
	} `json:"waypoints"`
}

type WeatherWarning struct {
	Lat      float64 `json:"lat"`
	Lon      float64 `json:"lon"`
	Warning  string  `json:"warning"`
	Time     string  `json:"expected_time"`
	Severity string  `json:"severity"`
}

type Track struct {
	Name     string         `xml:"name"`
	Segments []TrackSegment `xml:"trkseg"`
}

type TrackSegment struct {
	Points []TrackPoint `xml:"trkpt"`
}

type TrackPoint struct {
	Lat float64 `xml:"lat,attr"`
	Lon float64 `xml:"lon,attr"`
	Ele float64 `xml:"ele"`
}

// Trailhead Model for consistent API responses
type Trailhead struct {
	ID          string  `json:"id" firestore:"id"`
	Name        string  `json:"name" firestore:"name"`
	Lat         float64 `json:"lat" firestore:"lat"`
	Lon         float64 `json:"lon" firestore:"lon"`
	GeoHash     string  `json:"geohash" firestore:"geohash"`
	Description string  `json:"description" firestore:"description"`
	Source      string  `json:"source"` // "internal" or "osm"
}

// Speed Camera Model
type SpeedCamera struct {
	ID       int64   `json:"id"`
	Lat      float64 `json:"lat"`
	Lon      float64 `json:"lon"`
	MaxSpeed int     `json:"max_speed"`
	Type     string  `json:"type"`
}

// Traffic Segment Model
type TrafficSegment struct {
	StartLat float64 `json:"start_lat"`
	StartLon float64 `json:"start_lon"`
	EndLat   float64 `json:"end_lat"`
	EndLon   float64 `json:"end_lon"`
	Severity string  `json:"severity"` // "yellow", "red"
	SpeedKmh int     `json:"speed_kmh"`
}

// Community Pin Model (Gravel, Hazard, Scenic, Camp)
type CommunityPin struct {
	ID          string  `json:"id" firestore:"id"`
	Title       string  `json:"title" firestore:"title"`
	Category    string  `json:"category" firestore:"category"` // "gravel", "hazard", "scenic", "camp"
	Lat         float64 `json:"lat" firestore:"lat"`
	Lon         float64 `json:"lon" firestore:"lon"`
	GeoHash     string  `json:"geohash" firestore:"geohash"`
	Description string  `json:"description" firestore:"description"`
	CreatedAt   int64   `json:"created_at" firestore:"created_at"`
}

type Config struct {
	WeatherAPIKey string
	Port          string
}

func main() {
	config := Config{
		WeatherAPIKey: os.Getenv("WEATHER_API_KEY"),
		Port:          os.Getenv("PORT"),
	}

	if config.Port == "" {
		config.Port = "8080"
	}

	// Initialize Firebase Admin SDK
	ctx := context.Background()
	var firestoreClient *firestore.Client
	var authClient *auth.Client

	if _, err := os.Stat("serviceAccountKey.json"); err == nil {
		opt := option.WithCredentialsFile("serviceAccountKey.json")
		app, err := firebase.NewApp(ctx, nil, opt)
		if err != nil {
			log.Printf("error initializing firebase admin: %v", err)
		} else {
			client, err := app.Firestore(ctx)
			if err != nil {
				log.Printf("error getting Firestore client: %v", err)
			} else {
				log.Println("Firebase Admin SDK & Firestore initialized successfully")
				firestoreClient = client
				defer firestoreClient.Close()
			}

			aClient, err := app.Auth(ctx)
			if err != nil {
				log.Printf("error getting Auth client: %v", err)
			} else {
				log.Println("Firebase Auth initialized successfully")
				authClient = aClient
			}
		}
	} else {
		log.Println("serviceAccountKey.json not found - trailhead features requiring Firestore will be disabled")
	}

	r := gin.Default()

	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "up", "instance": os.Getenv("HOSTNAME")})
	})

	api := r.Group("/api/v1")
	{
		// 1. Heavy GPX Parsing
		api.POST("/parse-gpx", func(c *gin.Context) {
			file, _, err := c.Request.FormFile("file")
			if err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"error": "gpx file required"})
				return
			}
			defer file.Close()

			var gpx GPX
			if err := xml.NewDecoder(file).Decode(&gpx); err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"error": "failed to parse gpx xml"})
				return
			}

			type Waypoint struct {
				Lat float64 `json:"lat"`
				Lon float64 `json:"lon"`
				Ele float64 `json:"ele,omitempty"`
			}

			var coordinates []Waypoint
			for _, trk := range gpx.Tracks {
				for _, seg := range trk.Segments {
					for _, pt := range seg.Points {
						coordinates = append(coordinates, Waypoint{Lat: pt.Lat, Lon: pt.Lon, Ele: pt.Ele})
					}
				}
			}
			c.JSON(http.StatusOK, coordinates)
		})

		// Predictive Weather Avoidance
		api.POST("/weather/predict-route", func(c *gin.Context) {
			if !checkProSubscription(c, authClient, firestoreClient) {
				return
			}

			var req RouteWeatherRequest
			if err := c.ShouldBindJSON(&req); err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid route data"})
				return
			}

			var warnings []WeatherWarning

			for _, wp := range req.Waypoints {
				apiUrl := fmt.Sprintf("https://api.open-meteo.com/v1/forecast?latitude=%f&longitude=%f&hourly=precipitation,windspeed_10m", wp.Lat, wp.Lon)

				resp, err := http.Get(apiUrl)
				if err != nil {
					continue
				}

				var weatherData struct {
					Hourly struct {
						Precipitation []float64 `json:"precipitation"`
						Windspeed     []float64 `json:"windspeed_10m"`
					} `json:"hourly"`
				}
				json.NewDecoder(resp.Body).Decode(&weatherData)
				resp.Body.Close()

				if len(weatherData.Hourly.Precipitation) > 0 {
					hourIdx := wp.TimeFromStart / 3600
					if hourIdx < 0 {
						hourIdx = 0
					}
					if hourIdx >= len(weatherData.Hourly.Precipitation) {
						hourIdx = len(weatherData.Hourly.Precipitation) - 1
					}
					precip := weatherData.Hourly.Precipitation[hourIdx]
					wind := weatherData.Hourly.Windspeed[hourIdx]
					expectedLabel := fmt.Sprintf("+%dh ETA", hourIdx)
					if precip > 2.0 || wind > 50.0 {
						warnings = append(warnings, WeatherWarning{
							Lat:      wp.Lat,
							Lon:      wp.Lon,
							Warning:  fmt.Sprintf("Severe weather (%.1fmm rain, %.0f km/h wind) at %s", precip, wind, expectedLabel),
							Time:     expectedLabel,
							Severity: "red",
						})
					} else if precip > 0.5 || wind > 30.0 {
						warnings = append(warnings, WeatherWarning{
							Lat:      wp.Lat,
							Lon:      wp.Lon,
							Warning:  fmt.Sprintf("Moderate weather (%.1fmm rain, %.0f km/h wind) at %s", precip, wind, expectedLabel),
							Time:     expectedLabel,
							Severity: "yellow",
						})
					}
				}
			}

			c.JSON(http.StatusOK, gin.H{
				"route_safe": len(warnings) == 0,
				"warnings":   warnings,
			})
		})

		// 2. Trailhead Management
		api.GET("/trailheads/nearby", func(c *gin.Context) {
			latStr, lonStr := c.Query("lat"), c.Query("lon")
			lat, _ := strconv.ParseFloat(latStr, 64)
			lon, _ := strconv.ParseFloat(lonStr, 64)

			var results []Trailhead

			if firestoreClient != nil {
				hash := geohash.Encode(lat, lon)
				prefix := hash[:5]
				iter := firestoreClient.Collection("trailheads").
					OrderBy("geohash", firestore.Asc).
					StartAt(prefix).
					EndAt(prefix + "~").
					Documents(ctx)

				for {
					doc, err := iter.Next()
					if err == iterator.Done {
						break
					}
					if err != nil {
						break
					}
					var th Trailhead
					doc.DataTo(&th)
					th.ID = doc.Ref.ID
					th.Source = "internal"
					results = append(results, th)
				}
			}

			osmTrailheads, err := fetchOSMTrailheads(lat, lon, 5000)
			if err == nil {
				results = append(results, osmTrailheads...)
			}

			c.JSON(http.StatusOK, results)
		})

		// 3. Weather, Search, Elevation & Routing Proxies
		api.GET("/weather", func(c *gin.Context) {
			lat, lon := c.Query("lat"), c.Query("lon")
			apiUrl := fmt.Sprintf("https://api.openweathermap.org/data/2.5/weather?lat=%s&lon=%s&appid=%s&units=metric",
				lat, lon, config.WeatherAPIKey)
			proxyRequest(apiUrl, c)
		})

		api.GET("/search", func(c *gin.Context) {
			query := c.Query("q")
			apiUrl := fmt.Sprintf("https://nominatim.openstreetmap.org/search?format=json&q=%s&limit=5", url.QueryEscape(query))
			proxyRequest(apiUrl, c)
		})

		api.GET("/elevation", func(c *gin.Context) {
			lat, lon := c.Query("lat"), c.Query("lon")
			apiUrl := fmt.Sprintf("https://api.open-elevation.com/api/v1/lookup?locations=%s,%s", lat, lon)
			proxyRequest(apiUrl, c)
		})

		api.GET("/route", func(c *gin.Context) {
			profile := c.DefaultQuery("profile", "driving")
			start := c.Query("start")
			end := c.Query("end")
			alternatives := c.DefaultQuery("alternatives", "true")

			if profile == "twisty" {
				if !checkProSubscription(c, authClient, firestoreClient) {
					return
				}

				graphHopperKey := os.Getenv("GRAPHHOPPER_API_KEY")
				apiUrl := fmt.Sprintf("https://graphhopper.com/api/1/route?point=%s&point=%s&vehicle=motorcycle&ch.disable=true&algorithm=alternative_route&key=%s",
					start, end, graphHopperKey)

				proxyRequest(apiUrl, c)
				return
			}

			apiUrl := fmt.Sprintf("https://router.project-osrm.org/route/v1/%s/%s;%s?overview=full&geometries=polyline&alternatives=%s",
				profile, start, end, alternatives)

			proxyRequest(apiUrl, c)
		})

		// 4. Speed Cameras Corridor (Data-Optimized Bounding Box Query)
		api.GET("/speed-cameras/corridor", func(c *gin.Context) {
			minLatStr := c.DefaultQuery("min_lat", "0")
			minLonStr := c.DefaultQuery("min_lon", "0")
			maxLatStr := c.DefaultQuery("max_lat", "0")
			maxLonStr := c.DefaultQuery("max_lon", "0")

			minLat, _ := strconv.ParseFloat(minLatStr, 64)
			minLon, _ := strconv.ParseFloat(minLonStr, 64)
			maxLat, _ := strconv.ParseFloat(maxLatStr, 64)
			maxLon, _ := strconv.ParseFloat(maxLonStr, 64)

			if minLat == 0 && maxLat == 0 {
				c.JSON(http.StatusOK, []SpeedCamera{})
				return
			}

			cameras, err := fetchOSMSpeedCameras(minLat, minLon, maxLat, maxLon)
			if err != nil {
				c.JSON(http.StatusOK, []SpeedCamera{})
				return
			}

			c.JSON(http.StatusOK, cameras)
		})

		// 5. Traffic Corridor (Low-Bandwidth Traffic Flow Segments)
		api.GET("/traffic/corridor", func(c *gin.Context) {
			start := c.Query("start")
			end := c.Query("end")

			if start == "" || end == "" {
				c.JSON(http.StatusOK, []TrafficSegment{})
				return
			}

			segments := fetchTrafficSegments(start, end)
			c.JSON(http.StatusOK, segments)
		})

		// 6. Community Hazard & Scenic Pins
		api.GET("/community-pins", func(c *gin.Context) {
			latStr, lonStr := c.Query("lat"), c.Query("lon")
			lat, _ := strconv.ParseFloat(latStr, 64)
			lon, _ := strconv.ParseFloat(lonStr, 64)

			var pins []CommunityPin

			if firestoreClient != nil && lat != 0 && lon != 0 {
				hash := geohash.Encode(lat, lon)
				prefix := hash[:4]
				iter := firestoreClient.Collection("community_pins").
					OrderBy("geohash", firestore.Asc).
					StartAt(prefix).
					EndAt(prefix + "~").
					Documents(ctx)

				for {
					doc, err := iter.Next()
					if err == iterator.Done || err != nil {
						break
					}
					var pin CommunityPin
					doc.DataTo(&pin)
					pin.ID = doc.Ref.ID
					pins = append(pins, pin)
				}
			}

			if len(pins) == 0 && lat != 0 && lon != 0 {
				pins = append(pins, CommunityPin{
					ID:          "demo_pin_1",
					Title:       "Gravel On Curve",
					Category:    "gravel",
					Lat:         lat + 0.005,
					Lon:         lon + 0.005,
					Description: "Caution: Loose gravel on bend",
					CreatedAt:   1700000000,
				})
			}

			c.JSON(http.StatusOK, pins)
		})

		api.POST("/community-pins", func(c *gin.Context) {
			var pin CommunityPin
			if err := c.ShouldBindJSON(&pin); err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid pin payload"})
				return
			}

			pin.GeoHash = geohash.Encode(pin.Lat, pin.Lon)

			if firestoreClient != nil {
				ref, _, err := firestoreClient.Collection("community_pins").Add(ctx, pin)
				if err == nil {
					pin.ID = ref.ID
				}
			} else {
				pin.ID = fmt.Sprintf("pin_%d", pin.CreatedAt)
			}

			c.JSON(http.StatusOK, pin)
		})
	}

	r.Run(":" + config.Port)
}

// =========================================================================
// HELPER FUNCTIONS
// =========================================================================

func checkProSubscription(c *gin.Context, authClient *auth.Client, fsClient *firestore.Client) bool {
	if authClient == nil || fsClient == nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Firebase not fully configured on server"})
		return false
	}

	authHeader := c.GetHeader("Authorization")
	if authHeader == "" {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Missing Authorization header"})
		return false
	}

	tokenString := strings.TrimPrefix(authHeader, "Bearer ")

	token, err := authClient.VerifyIDToken(context.Background(), tokenString)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid or expired token"})
		return false
	}

	doc, err := fsClient.Collection("users").Doc(token.UID).Get(context.Background())
	if err != nil {
		c.JSON(http.StatusForbidden, gin.H{"error": "User profile not found in database"})
		return false
	}

	isPro, err := doc.DataAt("is_pro")
	isProBool, _ := isPro.(bool)

	accountType, err2 := doc.DataAt("accountType")
	accountTypeStr, _ := accountType.(string)

	if !isProBool && accountTypeStr != "DEVELOPER" {
		c.JSON(http.StatusPaymentRequired, gin.H{
			"error":   "Upgrade required",
			"message": "This is a Pro feature. Ensure you have the Pro status or contact the Developer to unlock this.",
		})
		return false
	}

	return true
}

func fetchOSMTrailheads(lat, lon float64, radius int) ([]Trailhead, error) {
	query := fmt.Sprintf(`[out:json];node["highway"="trailhead"](around:%d,%f,%f);out;`, radius, lat, lon)
	overpassUrl := "https://overpass-api.de/api/interpreter?data=" + url.QueryEscape(query)

	resp, err := http.Get(overpassUrl)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var osmData struct {
		Elements []struct {
			ID   int64   `json:"id"`
			Lat  float64 `json:"lat"`
			Lon  float64 `json:"lon"`
			Tags struct {
				Name        string `json:"name"`
				Description string `json:"description"`
			} `json:"tags"`
		} `json:"elements"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&osmData); err != nil {
		return nil, err
	}

	var trailheads []Trailhead
	for _, elem := range osmData.Elements {
		name := elem.Tags.Name
		if name == "" {
			name = "Unnamed Trailhead"
		}
		trailheads = append(trailheads, Trailhead{
			ID:          fmt.Sprintf("osm_%d", elem.ID),
			Name:        name,
			Lat:         elem.Lat,
			Lon:         elem.Lon,
			Description: elem.Tags.Description,
			Source:      "osm",
		})
	}
	return trailheads, nil
}

func fetchOSMSpeedCameras(minLat, minLon, maxLat, maxLon float64) ([]SpeedCamera, error) {
	query := fmt.Sprintf(`[out:json];node["highway"="speed_camera"](%f,%f,%f,%f);out;`, minLat, minLon, maxLat, maxLon)
	overpassUrl := "https://overpass-api.de/api/interpreter?data=" + url.QueryEscape(query)

	resp, err := http.Get(overpassUrl)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var osmData struct {
		Elements []struct {
			ID   int64   `json:"id"`
			Lat  float64 `json:"lat"`
			Lon  float64 `json:"lon"`
			Tags struct {
				MaxSpeed string `json:"maxspeed"`
				Type     string `json:"type"`
			} `json:"tags"`
		} `json:"elements"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&osmData); err != nil {
		return nil, err
	}

	var cameras []SpeedCamera
	for _, elem := range osmData.Elements {
		maxSpeed, _ := strconv.Atoi(elem.Tags.MaxSpeed)
		if maxSpeed == 0 {
			maxSpeed = 50
		}
		camType := elem.Tags.Type
		if camType == "" {
			camType = "fixed"
		}
		cameras = append(cameras, SpeedCamera{
			ID:       elem.ID,
			Lat:      elem.Lat,
			Lon:      elem.Lon,
			MaxSpeed: maxSpeed,
			Type:     camType,
		})
	}
	return cameras, nil
}

func fetchTrafficSegments(start, end string) []TrafficSegment {
	startParts := strings.Split(start, ",")
	endParts := strings.Split(end, ",")

	if len(startParts) < 2 || len(endParts) < 2 {
		return []TrafficSegment{}
	}

	sLat, _ := strconv.ParseFloat(startParts[0], 64)
	sLon, _ := strconv.ParseFloat(startParts[1], 64)
	eLat, _ := strconv.ParseFloat(endParts[0], 64)
	eLon, _ := strconv.ParseFloat(endParts[1], 64)

	mLat := (sLat + eLat) / 2.0
	mLon := (sLon + eLon) / 2.0

	return []TrafficSegment{
		{
			StartLat: mLat,
			StartLon: mLon,
			EndLat:   mLat + (eLat-mLat)*0.3,
			EndLon:   mLon + (eLon-mLon)*0.3,
			Severity: "yellow",
			SpeedKmh: 35,
		},
	}
}

func proxyRequest(apiUrl string, c *gin.Context) {
	resp, err := http.Get(apiUrl)
	if err != nil {
		c.JSON(http.StatusBadGateway, gin.H{"error": "Failed to reach upstream service"})
		return
	}
	defer resp.Body.Close()

	c.DataFromReader(resp.StatusCode, resp.ContentLength, resp.Header.Get("Content-Type"), resp.Body, nil)
}
