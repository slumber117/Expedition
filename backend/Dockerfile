# Use a newer Go image to satisfy modern dependency requirements
FROM golang:1.23-bookworm as builder

# Install git, which is required for fetching some Go dependencies
RUN apt-get update && apt-get install -y git && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the source code
COPY . .

# Handle the case-sensitive service account key
RUN if [ -f "serviceACcountKey.json" ]; then mv serviceACcountKey.json serviceAccountKey.json; fi

# Clear potential local artifacts and initialize modules
# Using GOTOOLCHAIN=auto allows Go to download a newer compiler if needed by dependencies
ENV GOTOOLCHAIN=auto
RUN go mod tidy

# Build the application
RUN go build -o main .

# Use a clean base image for the final stage
FROM debian:bookworm-slim

# Install CA certificates for HTTPS requests
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*

WORKDIR /root/

# Copy the binary and the service account key (if it exists)
COPY --from=builder /app/main .
COPY --from=builder /app/serviceAccountKey.json* .

# Expose port 8080 for Cloud Run
EXPOSE 8080

# Run the backend
CMD ["./main"]
