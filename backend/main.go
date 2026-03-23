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
	"strings" // Added for string manipulation

	"cloud.google.com/go/firestore"
	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/auth" // 🌟 ADD THIS for Firebase Auth
	"github.com/gin-gonic/gin"
	"github.com/mmcloughlin/geohash"
	"google.golang.org/api/iterator"
	"google.golang.org/api/option"
)

// GPX Models for XML parsing
type GPX struct {
	XMLName xml.Name `xml:"gpx"`
	Tracks[]Track  `xml:"trk"`
}

type RouteWeatherRequest struct {
	StartTimeUnix int64 `json:"start_time_unix"`
	Waypoints[]struct {
		Lat           float64 `json:"lat"`
		Lon           float64 `json:"lon"`
		TimeFromStart int     `json:"time_from_start_seconds"`
	} `json:"waypoints"`
}

type WeatherWarning struct {
	Lat     float64 `json:"lat"`
	Lon     float64 `json:"lon"`
	Warning string  `json:"warning"`
	Time    string  `json:"expected_time"`
}

type Track struct {
	Name     string         `xml:"name"`
	Segments[]TrackSegment `xml:"trkseg"`
}

type TrackSegment struct {
	Points[]TrackPoint `xml:"trkpt"`
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
	var authClient *auth.Client // 🌟 NEW: Declare the Auth client

	// Handled case-insensitively in Dockerfile to be 'serviceAccountKey.json'
	if _, err := os.Stat("serviceAccountKey.json"); err == nil {
		opt := option.WithCredentialsFile("serviceAccountKey.json")
		app, err := firebase.NewApp(ctx, nil, opt)
		if err != nil {
			log.Printf("error initializing firebase admin: %v", err)
		} else {
			// Initialize Firestore
			client, err := app.Firestore(ctx)
			if err != nil {
				log.Printf("error getting Firestore client: %v", err)
			} else {
				log.Println("Firebase Admin SDK & Firestore initialized successfully")
				firestoreClient = client
				defer firestoreClient.Close()
			}

			// 🌟 NEW: Initialize Auth
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

			var coordinates[]Waypoint
			for _, trk := range gpx.Tracks {
				for _, seg := range trk.Segments {
					for _, pt := range seg.Points {
						coordinates = append(coordinates, Waypoint{Lat: pt.Lat, Lon: pt.Lon, Ele: pt.Ele})
					}
				}
			}
			c.JSON(http.StatusOK, coordinates)
		})

		// 🌟 PREMIUM FEATURE: Predictive Weather Avoidance (£5/month)
		api.POST("/weather/predict-route", func(c *gin.Context) {
			// CHECK PRO SUBSCRIPTION FIRST!
			if !checkProSubscription(c, authClient, firestoreClient) {
				return // Blocks the request if they aren't paying
			}

			// 1. Parse the route sent by the Kotlin app
			var req RouteWeatherRequest
			if err := c.ShouldBindJSON(&req); err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid route data"})
				return
			}

			var warnings[]WeatherWarning

			// 2. Loop through the waypoints
			for _, wp := range req.Waypoints {
				apiUrl := fmt.Sprintf("https://api.open-meteo.com/v1/forecast?latitude=%f&longitude=%f&hourly=precipitation,windspeed_10m", wp.Lat, wp.Lon)

				resp, err := http.Get(apiUrl)
				if err != nil {
					continue
				}

				var weatherData struct {
					Hourly struct {
						Precipitation []float64 `json:"precipitation"`
						Windspeed[]float64 `json:"windspeed_10m"`
					} `json:"hourly"`
				}
				json.NewDecoder(resp.Body).Decode(&weatherData)
				resp.Body.Close()

				// Simplified Logic: Check if the 1st hour has rain or high winds
				if len(weatherData.Hourly.Precipitation) > 0 && weatherData.Hourly.Precipitation[0] > 0.5 {
					warnings = append(warnings, WeatherWarning{
						Lat:     wp.Lat,
						Lon:     wp.Lon,
						Warning: "Heavy rain expected here when you arrive.",
					})
				}
			}

			// 3. Return the warnings to the Kotlin app to draw icons on the map
			c.JSON(http.StatusOK, gin.H{
				"route_safe": len(warnings) == 0,
				"warnings":   warnings,
			})
		})

		// 2. Trailhead Management (Hybrid: Firestore + OpenStreetMap)
		api.GET("/trailheads/nearby", func(c *gin.Context) {
			latStr, lonStr := c.Query("lat"), c.Query("lon")
			lat, _ := strconv.ParseFloat(latStr, 64)
			lon, _ := strconv.ParseFloat(lonStr, 64)

			var results[]Trailhead

			// A. Query Internal Firestore Trailheads
			if firestoreClient != nil {
				hash := geohash.Encode(lat, lon)
				prefix := hash[:5] // ~5km radius
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

			// B. Query Public Data from OpenStreetMap
			osmTrailheads, err := fetchOSMTrailheads(lat, lon, 5000)
			if err == nil {
				results = append(results, osmTrailheads...)
			}

			c.JSON(http.StatusOK, results)
		})

		// 3. Weather & Search Proxies (Hides Keys)
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

			// 🌟 PREMIUM TIER: The "Twisty" Route (£5/month)
			if profile == "twisty" {

				// CHECK PRO SUBSCRIPTION FIRST!
				if !checkProSubscription(c, authClient, firestoreClient) {
					return // Blocks the request if they aren't paying
				}

				graphHopperKey := os.Getenv("GRAPHHOPPER_API_KEY")

				// ch.disable=true & algorithm=alternative_route forces it to find scenic/curvy paths
				apiUrl := fmt.Sprintf("https://graphhopper.com/api/1/route?point=%s&point=%s&vehicle=motorcycle&ch.disable=true&algorithm=alternative_route&key=%s",
					start, end, graphHopperKey)

				proxyRequest(apiUrl, c)
				return // Important: stop here so we don't run the free tier code
			}

			// 🟢 FREE TIER: Standard A-to-B Routing (Fastest Route)
			apiUrl := fmt.Sprintf("https://router.project-osrm.org/route/v1/%s/%s;%s?overview=full&geometries=polyline",
				profile, start, end)

			proxyRequest(apiUrl, c)
		})
	}

	r.Run(":" + config.Port)
}

// =========================================================================
// HELPER FUNCTIONS (Placed outside of main)
// =========================================================================

// checkProSubscription verifies the Firebase token and checks Firestore for Pro status
func checkProSubscription(c *gin.Context, authClient *auth.Client, fsClient *firestore.Client) bool {
	if authClient == nil || fsClient == nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Firebase not fully configured on server"})
		return false
	}

	// 1. Get the token from the header: "Authorization: Bearer <token>"
	authHeader := c.GetHeader("Authorization")
	if authHeader == "" {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Missing Authorization header"})
		return false
	}

	tokenString := strings.TrimPrefix(authHeader, "Bearer ")

	// 2. Verify the token with Firebase
	token, err := authClient.VerifyIDToken(context.Background(), tokenString)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid or expired token"})
		return false
	}

	// 3. Check the user's document in Firestore (Assuming you have a 'users' collection)
	doc, err := fsClient.Collection("users").Doc(token.UID).Get(context.Background())
	if err != nil {
		c.JSON(http.StatusForbidden, gin.H{"error": "User profile not found in database"})
		return false
	}

	// 4. Check if they have a boolean field called "is_pro" set to true, OR if they are a DEVELOPER
	isPro, err := doc.DataAt("is_pro")
	isProBool, _ := isPro.(bool)
	
	accountType, err2 := doc.DataAt("accountType")
	accountTypeStr, _ := accountType.(string)

	if (!isProBool && accountTypeStr != "DEVELOPER") {
		c.JSON(http.StatusPaymentRequired, gin.H{
			"error":   "Upgrade required",
			"message": "This is a Pro feature. Ensure you have the Pro status or contact the Developer to unlock this.",
		})
		return false
	}

	// User is authenticated AND has a Pro subscription!
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
		Elements[]struct {
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

	var trailheads