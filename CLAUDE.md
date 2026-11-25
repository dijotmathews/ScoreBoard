# ScoreBoard: handover guide for AI agents

Read this before you change anything. [`README.md`](README.md) covers what the app does for
people. This file covers how the code works, the conventions it follows and the work that
is still open.

## What it is

ScoreBoard is a tournament app (badminton-style knockouts) for three kinds of user:

- **Admin** (`ROLE_ADMIN`)
  - Imports or adds players and records their entry payments. A payment signs the player up.
  - Creates singles or doubles events, fills and locks the draws, and schedules matches
    (court, time and official).
  - Confirms results.
- **Official** (`ROLE_OFFICIAL`): enters results for their matches. The service also
  lets officials record payments, hand over kits and redeem coupons, but today only the
  admin's player dialog (`PlayerEditorDialog`) has buttons for kits and coupons.
- **Player** (`ROLE_USER`)
  - Sees their to-do list (payment, contact number), their matches, the draws and their
    profile.

It runs live at https://scoreboard.enigma-tech.in. Most users are on phones, so every
screen must work at about 400 px wide.

## Stack

- Java 25, Spring Boot 4.1, Vaadin Flow 25.3 (Aura theme). Views are server-side Java
  only, with no React or TypeScript views.
- **Database:** SQLite through `JdbcTemplate`, with no JPA.
  - Hikari has a pool of 1, and `PRAGMA foreign_keys=ON`.
  - Locally the database is `./app.db`. In production it is `/data/app.db`, in WAL mode,
    replicated to S3 by Litestream.
- **Security:** Spring Security with `JdbcUserDetailsManager` on the `users` and
  `authorities` tables, and BCrypt passwords.
  - Views use `@RolesAllowed`.
  - Every service method has its own `@PreAuthorize`. This is the real security boundary,
    so keep it on any new method.
- **Tests:** JUnit 5 with Vaadin's browserless tests (`browserless-test-spring`), running
  against a throwaway SQLite file.

The Vaadin MCP server (`.mcp.json`) has the docs. Check an API with `get_java_symbol`
before you assume it exists, because 25.x added a lot. Playwright MCP is configured for
checking real screens.

## Commands

```bash
./mvnw                 # run in dev mode on http://localhost:8081 (opens a browser)
./mvnw test            # all tests (about 200, about 1–2 min); must be green before handing back
./mvnw test -Dtest=AdminHomeViewTest
./mvnw clean package   # production jar (also what the Dockerfile runs)
cd demo && ./run.sh    # re-record the phone demo video (see demo/README.md)
```

To total the test results, run
`grep -h "Tests run" target/surefire-reports/*.txt | awk -F'[ ,]+' '{t+=$3;f+=$5;e+=$7} END {print t, f, e}'`.

**Gotcha: a stale CSS file is served after a production build.** `./mvnw package` leaves
`styles.css.br` and `styles.css.gz` in `target/classes`. Dev mode then serves those old
compressed files instead of your edited `styles.css`, so a CSS change looks like it did
nothing. Run `./mvnw clean` (or delete those two files) after any prod build.

## Code map (`src/main/java/com/dijo`)

| Package | What lives there |
|---|---|
| `security/` | `SecurityConfig`, `RoleBasedLoginSuccessHandler` (sends each role to its home), `AdminBootstrap` (creates `admin` from `APP_BOOTSTRAP_ADMIN_PASSWORD` on an empty DB), `UserInitializer` (dev seed users when `app.seed-users=true`) |
| `db/` | `SchemaMigration`: idempotent upgrades of existing databases on startup (see below) |
| `benefits/` | Players and money. `PlayerBenefitsService` (fee settings, payments, kits, coupons), `PlayerAccountService` (create/edit players, `capitalizeName`, phone), `PlayerCsv` + `PlayerImportService` (CSV import), `DemoPlayersInitializer` (`app.seed-demo-players`). The package `README.md` holds the benefit business rules. |
| `events/` | Tournament. `EventService` (events), `DrawService` (slots, byes, doubles partners, lock/unlock), `MatchService` (bracket, schedule, results, confirm, reopen), `OfficialService`, records `Match`/`Event`/`DrawSlot`/…, `EventUpdates` (change notifications) |
| `ui/` | Vaadin views and dialogs, `MainLayout` (side nav per role), `LiveReload`, `BracketSvg` |

### Routes

| Route | View | Role |
|---|---|---|
| `/login` | `LoginView` | anyone |
| `/` | `HomeView` (a placeholder; after login `RoleBasedLoginSuccessHandler` sends each role to its own home) | ADMIN, USER |
| `/admin` | `AdminHomeView`: progress counts and the **Remaining matches** list | ADMIN |
| `/admin/players` | `AdminPlayersView`: Players and Benefits, Import CSV, Benefit settings | ADMIN |
| `/admin/events` | `AdminEventsView`: Manage events | ADMIN |
| `/admin/draw` | `AdminDrawView`: fill and lock a draw | ADMIN |
| `/admin/matches` | `AdminMatchesView`: an event's matches | ADMIN |
| `/admin/officials` | `AdminOfficialsView` | ADMIN |
| `/official` | `OfficialHomeView`: the official's matches and result entry | OFFICIAL |
| `/player` | `PlayerHomeView`: "To do" card, next matches, benefits | USER |
| `/player/matches`, `/player/draws`, `/player/profile` | `PlayerMatchesView`, `PlayerDrawsView`, `PlayerProfileView` | USER |

Styles are all in `src/main/resources/META-INF/resources/styles.css`, using Aura and Lumo
custom properties. Components get class names (for example `list-row match-row
to-confirm`, `todo-card`, `home-section-header`) rather than inline styles, wherever a
rule is reused.

## Domain rules

- **Money** is stored as integer **paise**: 50000 means ₹500.00.
- **Signed up** means a player has a row in `entry_payments`. Only signed-up players can
  be placed in a draw. A payment reference is unique across all players.
- **Names** are stored with each word capitalized (`PlayerAccountService.capitalizeName`):
  "MEERA PILLAI" is stored as "Meera Pillai". A player's username is their name in
  lowercase, with accents stripped and only letters and digits kept (for example
  `meerapillai`). A number is added when the username is already taken. That username is also the
  player's first password.
- **Events:**
  - The draw size is a power of 2.
  - `team_size` is 1 (singles) or 2 (doubles). In doubles the slot's `player_username`
    leads the team, and the partner is `partner_username`. Matches and results refer to
    the team by its lead player.
- **Bracket:**
  - Locking a draw creates every match (`MatchService.createBracket`).
  - Round 1 pairs adjacent slots, and the winner of match *m* goes to match (*m*+1)/2 of
    the next round.
  - Byes advance on their own.
  - Unlocking a draw deletes its matches. This is refused once a real result has been
    entered.
- **Match status** (`Match.status()`): `WAITING_FOR_PLAYERS`, then `SCHEDULED`, then
  `AWAITING_CONFIRMATION`, then `CONFIRMED`, or `NO_MATCH` for a bye.
  - An official's result is `AWAITING_CONFIRMATION`. It moves the winner on only once an
    admin confirms it.
  - An admin can save and confirm a result in one step.
  - Admin Home lists awaiting-confirmation matches first, highlighted orange, with a
    **Review & confirm** button.
- **Live updates:** services call `EventUpdates.publish(eventId)` inside the transaction.
  Views subscribe in `onAttach` through `LiveReload.subscribe(...)` and unsubscribe in
  `onDetach`. The reload runs through server push, as the viewing user. Follow this
  pattern for any new view that shows event data.

## Database and migrations

- `schema.sql`, `benefits-schema.sql` and `events-schema.sql` run on every start. Every
  statement is `CREATE … IF NOT EXISTS`, so these files only describe a **fresh** database.
- A live database is upgraded by `db/SchemaMigration`, a Spring `InitializingBean`.
- **To add a column**, do both of these:
  1. Add it to the `CREATE TABLE` in the schema file.
  2. Add `addColumnIfMissing(table, column, definition)` in
     `SchemaMigration.afterPropertiesSet()`.
- **For a data fix**, use a method like `capitalizeFullNames()`. Read the rows with
  `queryForList` first, then update them. SQLite fails if you update inside an open
  `jdbc.query` callback.
- Test migrations in `src/test/java/com/dijo/db/SchemaMigrationTest.java`.
- Never edit the production database by hand. `infra/reset-tournament.sh` is the only
  supported bulk reset, and it backs up first.

## Testing conventions

- **Service tests** extend `DatabaseTest`.
  - It empties all tables before each test.
  - Create data with `account(...)`, `player(...)` and `signedUpPlayer(...)`.
  - Switch users with `signIn(...)` or `as(user, role, () -> ...)`.
- **UI tests** extend `UiTest` (browserless, in-process, real services).
  - Sign in with `@WithMockUser(username=..., roles={...})`.
  - Create data with `data.account(...)` and `data.signedUpPlayer(...)`, and run admin-only
    setup inside `asAdmin(() -> ...)`.
  - Then `navigate(View.class)`, `find(Button.class).withText(..)`, `click("Text")`,
    `test(component).setValue(..)`, and check `notifications()`.
- Tests check what a user sees, such as row text (`getElement().getTextRecursively()`)
  and notification text. When you change UI wording, update the matching strings in
  `src/test`, and the labels in `demo/record.mjs`.
- Add or adjust a test with every behaviour change. Check that the whole suite is green
  before you report the work as done.
- To check a real screen, run the app on a copy of `app.db` in your scratch space, on a
  spare port:

  ```bash
  ./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:sqlite:/path/copy.db --server.port=8099"
  ```

  Then use Playwright at phone width (about 400 px). Don't change the user's real
  `app.db`.

## Writing style for UI text and docs

The owner cares about clear, plain wording.

- Short sentences in sentence case, with no jargon.
  - Good: "Draws ready", "Draw places filled", "Match results confirmed".
  - Good: "Add a mobile number so the organisers can reach you about your matches."
- Use British/Indian English ("organisers") and ₹ amounts.
- Give every icon-only control an aria label.

## Deployment (see `infra/README.md`)

- Terraform in `infra/` sets up the whole stack in ap-south-1:
  - CloudFront with an ACM certificate, in front of one EC2 t4g.small running Docker
    Compose (app and Litestream), with an EBS data volume and S3 backups.
  - The DNS CNAME is at GoDaddy.
- `.github/workflows/ci.yml` runs the tests and Terraform checks on branches and PRs.
- `.github/workflows/deploy.yml` runs on pushes to `main`:
  1. Runs the tests.
  2. Builds a `linux/arm64` image with buildx. The Dockerfile builds the jar natively, then
     assembles the arm64 runtime image.
  3. Pushes the image to ECR, tagged with the git SHA.
  4. Runs `/opt/scoreboard/deploy.sh <sha>` on the server through SSM. That script rolls
     back if the new container isn't healthy within 3 minutes.
- `infra/backend.hcl` is local and untracked (copied from `backend.hcl.example`). Don't
  commit it.

## Working agreement with the owner

- **Commit only when asked.** The owner usually reviews and commits the work themselves.
  Work on the current feature branch, never on `main`. Branches are named
  `feature/<topic>`, and changes go to `main` through PRs.
- Don't push, deploy, run Terraform or touch the live server unless explicitly asked.
- Explain what changed in plain words, including any test you added and the test totals.

## State at handover (2026-11-25)

- **Branch:** `feature/player-info`. The last commits are `f0f7eb3` ("corrected style,
  changed name format") and `e2355f5` ("search match").
- **Done but not committed yet:**
  - `PlayerHomeView`: a **To do** card that highlights a pending entry payment and a
    missing contact number, with a button to My Profile. Styled by `.todo-card` and
    `.todo-*` in `styles.css`, and tested by `PlayerHomeViewTest` (new).
  - `demo/`: a Playwright and ffmpeg pipeline that records a portrait 1080×1920 demo video
    (`demo/scoreboard-demo.mp4`, git-ignored). See `demo/README.md`.
  - `README.md`: a "Demo video" section and a note on name capitalization.
  - `.gitignore`: ignores the demo's generated files.
- The last full test run had 199 tests and 0 failures.
- **Open issue: the deploy fails at the image build.** GitHub Actions reported
  `buildx failed … process "/bin/sh -c ./mvnw -B clean package -DskipTests" did not
  complete successfully: exit code: 1`. The cause is not known yet, because the full CI
  log was never shared.
  - **Next step:** get the Maven output from that step (the Actions log, or
    `docker buildx build --platform linux/arm64 .` locally) and fix the actual error.
  - Things worth checking: the Vaadin production frontend build (node download or npm
    inside the build container), and memory in the build stage.
