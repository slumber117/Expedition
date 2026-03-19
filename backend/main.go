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
	"time"

	firebase "firebase.google.com/go/v4"
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

// Overpass API Response Models
type OverpassResponse struct {
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

type Config struct {
	WeatherAPIKey string
	Port           string
}

func main() {
	config := Config{
		WeatherAPIKey: os.Getenv("WEATHER_API_KEY"),
		Port:           os.Getenv("PORT"),
	}

	if config.Port == "" {
		config.Port = "8080"
	}

	// Initialize Firebase Admin SDK
	ctx := context.Background()
	opt := option.WithCredentialsFile("serviceAccountKey.json")
	app, err := firebase.NewApp(ctx, nil, opt)
	if err != nil {
		log.Printf("error initializing firebase admin: %v (backend will continue without admin features)", err)
	}

	firestoreClient, err := app.Firestore(ctx)
	if err != nil {
		log.Printf("error getting Firestore client: %v", err)
	} else {
		log.Println("Firebase Admin SDK & Firestore initialized successfully")
		defer firestoreClient.Close()
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

		// 2. Trailhead Management (Hybrid: Firestore + OpenStreetMap)
		api.GET("/trailheads/nearby", func(c *gin.Context) {
			latStr, lonStr := c.Query("lat"), c.Query("lon")
			lat, _ := strconv.ParseFloat(latStr, 64)
			lon, _ := strconv.ParseFloat(lonStr, 64)

			var results []Trailhead

			// A. Query Internal Firestore Trailheads first
			if firestoreClient != nil {
				hash := geohash.Encode(lat, lon)
				prefix := hash[:5] // ~5km radius
				iter := firestoreClient.Collection("trailheads").
					OrderBy("geohash", 0).
					StartAt(prefix).
					EndAt(prefix + "~").
					Documents(ctx)

				for {
					doc, err := iter.Next()
					if err == iterator.Done { break }
					if err != nil { break }
					var th Trailhead
					doc.DataTo(&th)
					th.ID = doc.Ref.ID
					th.Source = "internal"
					results = append(results, th)
				}
			}

			// B. Query Public Data from OpenStreetMap (Overpass API)
			// This fills in the gaps where you don't have internal data yet
			osmTrailheads, err := fetchOSMTrailheads(lat, lon, 5000) // 5km radius
			if err == nil {
				results = append(results, osmTrailheads...)
			}

			c.JSON(http.StatusOK, results)
		})

		// 3. Weather & Search Proxies (Hides Keys)
		api.GET("/weather", func(c *gin.Context) {
			apiUrl := fmt.Sprintf("https://api.openweathermap.org/data/2.5/weather?lat=%s&lon=%s&appid=%s&units=metric",
				c.Query("lat"), c.Query("lon"), config.WeatherAPIKey)
			proxyRequest(apiUrl, c)
		})

		api.GET("/search", func(c *gin.Context) {
			apiUrl := fmt.Sprintf("https://nominatim.openstreetmap.org/search?format=json&q=%s&limit=5", url.QueryEscape(c.Query("q")))
			proxyRequest(apiUrl, c)
		})
	}

	r.Run(":" + config.Port)
}

// Helper to fetch trailheads from OpenStreetMap
func fetchOSMTrailheads(lat, lon float64, radius int) ([]Trailhead, error) {
	// Overpass QL to find nodes tagged as trailhead near the coordinates
	query := fmt.Sprintf(`[out:json];node["highway"="trailhead"](around:%d,%f,%f);out;`, radius, lat, lon)
	overpassUrl := "https://overpass-api.de/api/interpreter?data=" + url.QueryEscape(query)

	resp, err := http.Get(overpassUrl)
	if err != nil { return nil, err }
	defer resp.Body.Close()

	var osmData OverpassResponse
	if err := json.NewDecoder(resp.Body).Decode(&osmData); err != nil { return nil, err }

	var trailheads []Trailhead
	for _, el := range osmData.Elements {
		name := el.Tags.Name
		if name == "" { name = "Unnamed Trailhead" }

		trailheads = append(trailheads, Trailhead{
			ID:          fmt.Sprintf("osm_%d", el.ID),
			Name:        name,
			Lat:         el.Lat,
			Lon:         el.Lon,
			Description: el.Tags.Description,
			Source:      "osm",
		})
	}
	return trailheads, nil
}

// Helper to proxy requests and handle JSON responses
func proxyRequest(apiUrl string, c *gin.Context) {
	req, _ := http.NewRequest("GET", apiUrl, nil)
	req.Header.Set("User-Agent", "ExpeditionBackend/1.0")
	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "upstream service failure"})
		return
	}
	defer resp.Body.Close()
	var data interface{}
	json.NewDecoder(resp.Body).Decode(&data)
	c.JSON(http.StatusOK, data)
}
