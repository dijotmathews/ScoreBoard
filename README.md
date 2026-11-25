# ScoreBoard

Draws, matches and player benefits for a tournament: a Vaadin Flow and Spring Boot app on SQLite.

## Importing players from CSV

On **Players and Benefits**, **Import CSV** creates players in bulk from the registration
form export (for example the Google Forms CSV). Each player gets a username made from their
name, which is also their initial password. Names are saved with each word capitalized,
however they were typed ("ABEL JOE MANOJ" becomes "Abel Joe Manoj"). When a row has a transaction reference, the
payment is recorded and the player is signed up, for the amount in the row or, if that is
blank, the entry fee set in Benefit settings. Rows already imported (same transaction
reference or same name) are skipped, so the same file can be uploaded again safely.

The first row must hold the column headings. Columns are found by their heading, so they
can be in any order, and other columns (timestamp, email, date of birth, …) are ignored.
Files can be comma- or tab-separated, up to 2 MB.

| Column | Required | Values | Matched by a heading containing |
|---|---|---|---|
| Players Name(AITA/AADHAR) | Yes | The player's full name | "player" and "name" |
| Payment Amount | Column yes; a cell may be blank (the entry fee is assumed) | e.g. `500`, `₹500`, `Rs. 1,000.50` | "amount" |
| Transaction Reference | Column yes; a blank cell creates the player without signing them up | e.g. the UPI reference | "transaction" or "reference" |
| Contact Number | No | 7–15 digits, with an optional leading `+`, spaces, `-` or brackets | "phone", "mobile", "WhatsApp" or "contact" |

A row without a transaction reference creates the player without signing them up. Signing
up needs the entry fee to be set in Benefit settings. An invalid contact number is left
out, and the player is still imported. Example (the import dialog offers it as
**Download sample CSV**):

```csv
Players Name(AITA/AADHAR),Contact Number,Payment Amount,Transaction Reference
Ada Li,+91 98765 43210,500,UPI123456789
Bo Chen,,,
```

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

## Demo video

[`demo/`](demo/README.md) records a short portrait walkthrough of the app for phones:
`cd demo && ./run.sh` builds `demo/scoreboard-demo.mp4` from a fresh demo database.

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
