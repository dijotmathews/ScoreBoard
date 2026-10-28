# ScoreBoard

Draws, matches and player benefits for a tournament: a Vaadin Flow and Spring Boot app on SQLite.

## Development

The app needs Java 25. Start it from the IDE by running `Application`, or from the command line:

```bash
./mvnw
```

It uses `./app.db`, which is created on first start. To create demo accounts, set
`app.seed-users=true` in `application.properties` (see `UserInitializer`).

Run the tests:

```bash
./mvnw test
```

## Production

Build the jar with `./mvnw package`, or the image with `docker build -t scoreboard .`. The image runs with
the `prod` profile (`application-prod.properties`):

- The database is `/data/app.db`, with SQLite's write-ahead log on. Mount a volume at `/data`.
- On an empty database, set `APP_BOOTSTRAP_ADMIN_PASSWORD` to create the first `admin`
  account (`AdminBootstrap`).
- The app expects to sit behind CloudFront, which terminates HTTPS.

```bash
docker run -p 8080:8080 -v scoreboard-data:/data -e APP_BOOTSTRAP_ADMIN_PASSWORD='change-me-now' scoreboard
```

## AWS

The app runs at https://scoreboard.enigma-tech.in on one EC2 instance in ap-south-1,
behind CloudFront, with Litestream backups to S3. Terraform in [`infra/`](infra/README.md)
sets it up.

- `.github/workflows/ci.yml` runs the tests and checks the Terraform on pull requests and branches.
- `.github/workflows/deploy.yml` runs on pushes to `main`. It tests the app, builds the
  `linux/arm64` image, pushes it to ECR and deploys it to the server through SSM.
