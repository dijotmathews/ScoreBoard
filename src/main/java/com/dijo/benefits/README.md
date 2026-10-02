# Player entry fee and benefits backend

Drop-in backend for the SQLite/JDBC Vaadin project supplied in the conversation.
Copy src/main/java into your project. Keep classes beneath your application's
component-scan package. No new dependencies beyond starter-jdbc and
starter-security are required.

Copy benefits-schema.sql into src/main/resources and set:

```properties
spring.sql.init.mode=always
spring.sql.init.schema-locations=classpath:schema.sql,classpath:benefits-schema.sql
spring.datasource.hikari.maximum-pool-size=1
spring.datasource.hikari.connection-init-sql=PRAGMA foreign_keys=ON
```

schema.sql is the existing users/authorities schema and must execute first.
If you already have @EnableMethodSecurity, keep just one configuration enabling it.
Use one application instance and a persistent SQLite file. This package does not
include a REST controller: Vaadin views inject the service directly. Method
security applies to calls through the injected Spring service, including calls
from Vaadin server-side click listeners.

## Business rules

- ADMIN configures event fee and coupons.
- ADMIN or OFFICIAL manually confirms an independently verified full payment.
- An enabled ROLE_USER account is treated as a player.
- One payment per player per event; a payment reference is globally unique.
- Money uses integer paise: 50000 means INR 500.00.
- Exact repeated confirmation returns the original benefits. A different reference
  for an already-paid player/event is rejected, not silently recorded.
- Payment, one kit entitlement and configured coupon entitlements commit together.
- Kit entitlement creation is distinct from physical handover.
- Each coupon represents one use of the configured description. It is NOT a
  monetary discount coupon; value, expiry and category rules are not specified.
- Handover/redemption writes use atomic conditional updates. Retrying returns false
  without changing the first operator or timestamp.
- No refund/cancellation, gateway integration, stock inventory or event enrollment
  workflow is included. Add these once their business rules are defined.

## Call from Vaadin views

Inject PlayerBenefitsService in each view constructor and retain it as a field.
Examples below are server-side calls, not HTTP endpoints.

```java
// AdminHomeView: example setup, create once (do not run on every navigation).
String eventId = service.createEvent("Tournament", 50000, 2, "Refreshment");

// OfficialHomeView: after independently checking a receipt/payment.
var benefits = service.confirmPayment(eventId, "dijo", "UPI:receipt-123", 50000);
boolean handedOverNow = service.handOverKit(eventId, "dijo");
boolean redeemedNow = service.redeemCoupon(eventId, "dijo", couponCode);

// PlayerHomeView: uses authenticated username; no player ID supplied by browser.
var mine = service.myBenefits(eventId);
boolean kitCollected = mine.kitHandedOverAt() != null;
```

An unpaid player's myBenefits call throws IllegalArgumentException; show a
"Payment not confirmed" message. Catch business validation errors in your views
and show a Notification. Unknown coupon and wrong ownership also produce a
validation error. Authorization failures should remain access-denied errors.
Persist/select the returned eventId in your UI. Use service.events() to populate
an event selector. An ADMIN with ROLE_USER can also access myBenefits.

## Payment gateway integration later

Do not expose confirmPayment as a player-accessible endpoint. A browser success
message does not verify payment. A gateway integration must verify the provider's
signature and authoritative payment status, match the order/player/event and
amount/currency, and then use a separate trusted service boundary. The current
method records an official's confirmation; it does not contact a provider.

## Verification

The included verify_sql.py checks the actual embedded SQL against SQLite for
idempotent payment confirmation, wrong amounts/players, unique references,
single-use coupons, kit handover, ownership, foreign keys and transaction rollback.
Run `python3 verify_sql.py`.
The Java source was not compiled against your Maven project. Run your project's
Maven compile/tests after integration and verify method security with ADMIN,
OFFICIAL and USER logins. No view or payment gateway code is modified here.

## Seed twelve demo players

DemoPlayersInitializer runs only with the dev profile AND app.seed-demo-players=true.
Use a development SQLite database, not real event/payment data. Run:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments="--app.seed-demo-players=true"
```

Creates/reuses demo event demo-tournament-12, entry fee INR 500, two Refreshment
coupons, and one welcome kit entitlement per player. Usernames player01 through
player12; initial passwords Player01@12345 through Player12@12345. Existing users
and passwords are preserved; incompatible/disabled accounts cause rollback.
An admin account is created if missing with development password Admin@12345.
An existing admin must be enabled and have ROLE_ADMIN. The initializer establishes
a temporary trusted admin context solely for startup calls and restores it afterward.
All twelve users/payments/benefits are seeded in one transaction. Repeating the
seed returns existing payment benefits without duplicating or resetting usage.
Kits remain pending handover and coupons remain unredeemed initially.
Generated users/authorities IDs are omitted from inserts and generated by SQLite.
After seeding, start normally without app.seed-demo-players=true.

## Admin players view

Copy the updated PlayerBenefitsService.java and new views/AdminPlayersView.java
into your app. AdminPlayersView uses your existing AdminHomeView class as its
back link. If that class is in another package, adjust the import.
Open /admin/players after logging in as ADMIN. Add navigation in AdminHomeView:

```java
add(new RouterLink("Players and Benefits", AdminPlayersView.class));
```

The view shows all ROLE_USER accounts except ADMIN/OFFICIAL staff, including
unpaid players for the selected event. It is not an event enrollment roster:
there is currently no enrollment table. Earlier dijo/user accounts will appear
as unpaid alongside the twelve demo players. Event selection, username search,
refresh, payment/reference, kit status, issued/redeemed/remaining coupon counts
and filtered summary totals are included. No passwords or coupon codes appear.
The read service method and route are both admin-only. Current implementation
loads the player list into memory; use lazy paging for much larger rosters.
SQL listing tests pass; Java compilation and visual browser QA remain pending
in the supplied Java 25/Vaadin 25 project.
