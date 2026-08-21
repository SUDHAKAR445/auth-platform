# Architecture Decision Records

Every entry here follows the same shape: the problem we faced, the options we
weighed, what we picked, and — most importantly for an interview — *why*, and what
we knowingly gave up.

---

## Decision 1: JWT instead of server-side sessions

**Context:** After login, the API needs to recognize the caller on every subsequent
request without asking for a password again.

**Options considered:**
- Server-side sessions (session ID in a cookie, session state in memory or a shared
  store like Redis)
- A signed JWT the client holds and presents on every request

**Decision:** JWT (`JwtService`, HMAC-signed, 15-minute expiry).

*Implementation note:* `JwtService` doesn't hardcode an algorithm — it builds the
signing key with `Keys.hmacShaKeyFor(secretBytes)`, which auto-selects the strongest
HMAC-SHA variant the key length supports (≥64 bytes → HS512, 48–63 → HS384,
32–47 → HS256). Our dev secret is 69 bytes, so tokens are actually signed HS512, not
HS256 — worth knowing if you ever hardcode an algorithm expectation elsewhere (e.g. a
client-side JWT library that assumes HS256).

**Why:** `auth-platform` is explicitly a multi-module system with a `gateway` module
already scaffolded and more services planned (`user-service`, `notification`). A
session ID means every service that needs to know "who is this" has to call back to
a shared session store on every request. A JWT carries identity *in the token itself*
— any service can verify the signature locally and know who's calling, with zero
shared state and zero extra network hop. That property matters more as the system
grows past one service.

**Trade-off we accepted:** JWTs can't be revoked before they expire without extra
infrastructure — mitigated with a short 15-minute expiry plus a separate
refresh-token flow (see
[Decision 10](decisions.md#decision-10-opaque-database-backed-refresh-tokens-instead-of-a-second-jwt)).
We later closed most of this gap anyway: see
[Decision 15](decisions.md#decision-15-check-session-revocation-on-every-request-not-just-token-expiry),
where every request now re-checks the token's session, at the cost of the
statelessness this decision was originally chosen for.

---

## Decision 2: BCrypt for password hashing

**Context:** Passwords were stored in plaintext during the first pass of the
registration feature — clearly not acceptable past a throwaway prototype.

**Options considered:**
- Plaintext (rejected immediately — never acceptable past prototyping)
- A general-purpose hash (SHA-256/SHA-512) — fast, which is exactly the problem: fast
  hashes are cheap to brute-force at scale with GPUs
- BCrypt — a hashing algorithm deliberately designed to be *slow* and tunable

**Decision:** `BCryptPasswordEncoder` via a `PasswordEncoder` bean, used everywhere a
password is set or checked (`registerUser`, `login`).

**Why:** BCrypt automatically generates and stores a random salt per password (so two
users with the same password get completely different hashes) and its cost factor is
adjustable as hardware gets faster — the same algorithm still slows down attacks a
decade from now. It's also the standard first-class citizen in Spring Security, so
adopting it added zero extra dependencies.

**Trade-off we accepted:** BCrypt is intentionally slower than a general-purpose
hash — a deliberate cost we pay on every login, in exchange for making offline
password-cracking far more expensive for an attacker.

---

## Decision 3: One generic error for every login failure

**Context:** A login can fail for three different reasons: the email doesn't exist,
the password is wrong, or the account is disabled/suspended.

**Options considered:**
- Return a distinct message per cause (`"no account with that email"`,
  `"wrong password"`, `"account disabled"`)
- Return one identical message and status code for all three

**Decision:** All three throw the same `InvalidCredentialsException` →
`401 Unauthorized`, message: `"Invalid email or password"`.

**Why:** If "email not found" and "wrong password" return different messages, an
attacker can enumerate which emails are registered just by trying logins and reading
the error — a real, well-known vulnerability class (user enumeration). Making all
three failure paths indistinguishable from the outside closes that hole for free,
and it happened to give us "prepare for disabled-user support" (from the login
curriculum) at zero extra code — the status check just reuses the same exception.

**Trade-off we accepted:** Slightly worse UX — a legitimate user who mistyped their
email gets the same message as one who typed the wrong password, with no hint which.
That's the correct trade for an auth endpoint.

---

## Decision 4: Stateless authentication

**Context:** `SecurityConfig` needed a session policy.

**Options considered:**
- Default Spring Security sessions (server creates and tracks an `HttpSession`)
- `SessionCreationPolicy.STATELESS` (Spring Security never creates or reads a session)

**Decision:** `STATELESS`.

**Why:** This follows directly from Decision 1. If identity lives entirely in the JWT
on every request, keeping a server-side session around too is redundant state that
has to be kept in sync with the token — and it reintroduces the exact shared-state
problem across services that JWT was chosen to avoid. Stateless also means any
instance of `auth-service` can handle any request with no session-affinity routing
needed, which matters once this sits behind a load balancer or the planned gateway.

**Trade-off we accepted, later revisited:** Originally, a live access token couldn't
be revoked server-side before it naturally expired — the 15-minute expiry was the
ceiling on that exposure. We decided that ceiling was too loose (see
[Decision 15](decisions.md#decision-15-check-session-revocation-on-every-request-not-just-token-expiry))
and added a per-request session check, so a logged-out user's access token now stops
working on the very next request, not after up to 15 minutes.

---

## Decision 5: PostgreSQL instead of MongoDB

**Context:** An early version of this curriculum's plan called for MongoDB. We
built a second prototype project (`identity-platform`) on MongoDB's document model
before deciding to consolidate.

**Options considered:**
- MongoDB — schema-less documents, flexible, matches how some identity platforms
  (e.g., early-stage tools) start out
- PostgreSQL — relational, enforced schema, strong constraints

**Decision:** PostgreSQL, explicitly chosen over MongoDB when the two prototype
projects were merged into this one.

**Why:** User data here is fundamentally relational with hard invariants a
relational database enforces for free: an email must be unique (`UNIQUE` constraint,
not an application-level check that can race), a status must be one of a fixed set
of values (`CHECK` constraint), and every future feature on the roadmap (RBAC roles,
sessions, audit logs, device management) is naturally a foreign-key relationship to
`users`, not a document to embed or duplicate. A document store's flexibility is a
cost here, not a benefit, since the shape of a user record isn't expected to vary
per-row.

**Trade-off we accepted:** Every schema change needs an explicit migration path
(see Decision 8) — more upfront ceremony than MongoDB's "just write the new field"
model, in exchange for the database itself catching data-integrity bugs instead of
the application.

---

## Decision 6: Multi-module Maven layout from day one

**Context:** The project could have been one Spring Boot application, or split into
modules before any of the extra modules had real code in them.

**Options considered:**
- Single module, split later if/when it's actually needed
- Multi-module parent (`common`, `auth-service`, `user-service`, `notification`,
  `gateway`) from the start, even with most modules empty

**Decision:** Multi-module, kept when the two early prototypes were merged.

**Why:** The stated end goal is a system with JWT, RBAC, OAuth2, sessions,
notifications, and audit logging as largely independent concerns — that shape was
known upfront, not discovered later. Setting the module boundaries early means each
future feature has an obvious home from the first line of code, instead of a large
refactor later to carve a monolith apart along boundaries that get harder to see the
bigger the single module grows.

**Trade-off we accepted:** More boilerplate today — four mostly-empty module
skeletons with their own `pom.xml` — for a payoff that only shows up once those
modules get real code.

---

## Decision 7: Split registration, authentication, and profile lookup into separate services

**Context:** Registration, login, and "fetch a user's profile" all touch the same
`User` entity and `UserRepository`. They could live in one `UserService`.

**Options considered:**
- One `UserService` with `registerUser()`, `login()`, `getUserById()`
- Three services (`RegistrationService`, `AuthenticationService`, `UserService`),
  one per responsibility

**Decision:** Three services, split out incrementally as each feature landed.

**Why:** These three operations change for entirely different reasons: registration
logic changes when signup rules change (email verification, invite-only, etc.),
authentication changes when the login/token strategy changes (this is exactly what
happened — `AuthenticationService` grew a `JwtService` dependency when JWT was added,
which had nothing to do with fetching a profile by id). Keeping them separate means
adding JWT support touched one file's dependencies, not a shared god-class already
carrying two other responsibilities.

**Trade-off we accepted:** Slightly more files and constructor wiring for what is,
today, still a small codebase — a cost that pays for itself as each responsibility
keeps growing independently.

---

## Decision 8: `ddl-auto: update` for now, not a migration tool

**Context:** Every schema change so far (adding `password`, `status`,
`email_verified`; removing `username`) needed the database schema to change too.

**Options considered:**
- Hibernate's `ddl-auto: update` — infer and apply schema changes automatically at
  startup
- A migration tool (Flyway/Liquibase) — explicit, ordered, reviewable SQL scripts

**Decision:** `ddl-auto: update`, for now.

**Why:** For a solo learning project with no real user data at stake, letting
Hibernate infer the schema from the entity is the fastest path to iterating on the
`User` model without hand-writing SQL for every change.

**Trade-off we accepted, with eyes open:** `ddl-auto: update` only ever *adds*
columns/tables — it never drops or renames anything, and it can't add a `NOT NULL`
column to a table that already has rows without a `DEFAULT`. We hit this directly:
merging the two `User` models required manually dropping the `users` table (after
confirming it held only test data) because Hibernate can't perform that kind of
migration safely. With real user data, that exact change would have either crashed
the app on startup (adding `NOT NULL` columns with no default) or silently left rows
with `NULL` status/password, breaking login for existing users with no error message
pointing at the cause. This is explicitly *not* production-safe, and the plan is to
move to Flyway once the schema stabilizes.

---

## Decision 9: A custom `@StrongPassword` annotation instead of inline checks

**Context:** Password strength (min length, uppercase, lowercase, digit, special
character) needed to be enforced on registration.

**Options considered:**
- Check the rules imperatively inside `RegistrationService`
- A custom Bean Validation constraint (`@StrongPassword` + `ConstraintValidator`)
  applied directly on `RegisterRequest.password`

**Decision:** Custom annotation.

**Why:** Every other field constraint (`@NotBlank`, `@Email`, `@Size`) already lives
declaratively on the DTO and is enforced automatically by `@Valid` before the
controller method body even runs. Checking password strength imperatively in the
service would split validation logic across two different places for no reason —
the annotation keeps every input rule for `RegisterRequest` visible in one place, and
it's automatically picked up by Swagger's schema generation too.

**Trade-off we accepted:** A small amount of Bean Validation SPI boilerplate
(`ConstraintValidator<StrongPassword, String>`) that a simple `if` statement wouldn't
have needed — worth it for consistency with the rest of the DTO's validation style.

---

## Decision 10: Opaque, database-backed refresh tokens instead of a second JWT

**Context:** The access token expires in 15 minutes by design (Decision 1). Once it
expires, the client needs a way to get a new one without forcing the user to log in
with their password again.

**Options considered:**
- Issue a second, longer-lived JWT as the "refresh token"
- Issue an opaque random string, stored server-side in its own `refresh_tokens`
  table, looked up by exact value on each refresh

**Decision:** Opaque token, tracked in Postgres (`RefreshToken` entity —
`userId, token, expiresAt, revoked, deviceId`).

**Why:** A JWT's whole appeal (Decision 1) is that it needs no server-side record to
verify — but that's exactly the wrong property for a *refresh* token, which must be
revocable (logout, a stolen device, rotation on reuse). A JWT refresh token would
still be valid until it expired even if we wanted to kill it right now; there'd be no
row to delete or flag. Making it an opaque, database-tracked value gives us a real
place to revoke from — `findByToken` + `revoked = true` — while the access token
stays exactly as stateless and cheap-to-verify as Decision 1 intended. The two
tokens deliberately have different trust models: short-lived and self-verifying vs.
long-lived and server-checked.

**Trade-off we accepted:** Every refresh now costs one database round-trip
(`findByToken`, plus the user lookup to reissue an access token) — the exact cost
JWTs were chosen to avoid for regular requests. That's acceptable here because
refreshing happens roughly once per 15 minutes per user, not on every request.

---

## Decision 11: Rotate and revoke on every refresh, never reuse a refresh token

**Context:** When a refresh token is used to get a new access token, the old refresh
token could either stay valid until its own expiry (7 days), or be invalidated
immediately and replaced.

**Options considered:**
- Reuse: the same refresh token stays valid across multiple refresh calls until it
  naturally expires
- Rotate: every refresh call issues a *brand new* refresh token and immediately
  marks the old one `revoked = true`; using an already-revoked token fails

**Decision:** Rotate on every call (`RefreshTokenService.rotateRefreshToken()`).

**Why:** Rotation turns refresh-token theft into a detectable event instead of a
silent one. If an attacker steals a refresh token and uses it, and the real user's
client *also* tries to use that same (now-revoked) token later, the second attempt
fails loudly — that's a concrete signal something is wrong, which reuse would never
produce (a stolen-and-reused token would look completely normal). We chose to soft-
revoke (`revoked = true`) rather than delete the old row, so there's still an audit
trail of the rotation chain if we ever need to investigate a compromised account.

**Trade-off we accepted:** Slightly more writes per refresh (revoke old + insert
new, instead of just reading the old one) — a small, fixed cost in exchange for a
real security signal instead of none.

---

## Decision 12: Session as a separate entity from RefreshToken

**Context:** Adding "show me my active devices" and "log out this device"/"log out
everywhere" needed somewhere to track *which device* each refresh token belongs to,
its IP, its user-agent, and when it was last used. `RefreshToken` already existed
and could have grown these columns directly.

**Options considered:**
- Add device/IP/user-agent/last-used columns straight onto `RefreshToken`
- A separate `Session` entity, one row per logged-in device, referencing the current
  refresh token by value

**Decision:** Separate `Session` table, kept in sync with `RefreshToken` by
`RefreshTokenService` (which now owns both).

**Why:** `RefreshToken` is a security-critical, minimal record — its only jobs are
"is this token valid" and "revoke it." `Session` is a UX/audit-facing record with a
completely different reason to change (a new field for "list your devices" someday,
or richer device parsing, has nothing to do with token validity logic). Bolting
device metadata onto `RefreshToken` would mean every future session-management
feature touches the same class that also handles security-critical revocation
checks — exactly the kind of god-class growth Decision 7 already rejected once.
Keeping them separate also matches the actual flows we were asked to build: logout
explicitly *validates and revokes the token, then separately deletes the session* —
two distinct operations on two distinct records, not one.

**Trade-off we accepted:** Two tables to keep in sync instead of one — every place
that creates or rotates a refresh token must also remember to create or update its
session row. We contained this by making `RefreshTokenService` the *only* class that
touches both (see [Decision 13](decisions.md#decision-13-one-service-owns-both-refreshtoken-and-session-to-avoid-a-circular-dependency)),
so "keeping them in sync" is centralized in one place, not scattered.

---

## Decision 13: One service owns both RefreshToken and Session, to avoid a circular dependency

**Context:** Logging out needs to revoke a `RefreshToken` *and* delete its `Session`.
Rotating a refresh token needs to update its `Session`'s last-used time and token
value. Both operations touch both entities — the question is which service is
allowed to depend on which.

**Options considered:**
- `RefreshTokenService` and `SessionService` each call into the other as needed
- Only one of them is allowed to depend on the other; the other stays "pure"

**Decision:** `RefreshTokenService` depends on `SessionService`. `SessionService`
depends on nothing but `SessionRepository` — it never calls back into
`RefreshTokenService`.

**Why:** Letting both services call each other creates a circular bean dependency —
Spring can't construct either one first, since each needs the other already built.
Rather than reach for constructor tricks to work around that, the actual fix is
architectural: pick one direction. `RefreshTokenService` was the natural owner,
since "generate/rotate/revoke a refresh token" was already its job, and a session is
really just "the device that refresh token belongs to" — a detail of the token's
lifecycle, not a peer concept that needs equal say.

**Trade-off we accepted:** `SessionService` can't independently trigger a token
revocation (e.g. it has no way to say "this session looks suspicious, kill its
token") — that logic would have to live in whatever calls both services, since
`SessionService` isn't allowed to reach for `RefreshTokenService` itself. Not a
concern for anything asked for so far.

---

## Decision 14: `/logout` is public, `/logout-all` requires a valid access token

**Context:** Both endpoints end a user's login state, but they were specified with
different inputs: `/logout` takes a refresh token in the body; `/logout-all` takes
no body at all.

**Options considered:**
- Require a valid access token for both
- `/logout` public (refresh token is the only proof needed), `/logout-all` behind
  the standard `anyRequest().authenticated()` rule

**Decision:** Asymmetric — `/logout` added to the public route list,
`/logout-all` left protected.

**Why:** `/logout` has a concrete token in its request body to validate against —
that's sufficient proof of intent, and it has to be public because a client logging
out may have *already* let its 15-minute access token expire while still holding a
valid refresh token; requiring a fresh access token just to log out would be a dead
end for that client. `/logout-all` has no such token in its request — the *only*
way to know whose sessions to wipe is to read it off a currently-valid JWT, which
means it can't be public by construction, not just by policy choice.

**Trade-off we accepted:** A stolen refresh token alone is enough to call `/logout`
on someone else's session — but that only lets an attacker log the real user out,
which is a nuisance, not a compromise (they still can't read data or get a new
access token without the token also being valid, which `/logout` immediately makes
false anyway).

---

## Decision 15: Check session revocation on every request, not just token expiry

**Context:** `JwtAuthenticationFilter` only checked a token's signature and its own
`exp` claim — pure math, no database lookup, exactly per Decision 1. That meant
`/logout` and `/logout-all` only ever stopped a client from getting a *new* access
token; any access token already issued kept working, fully authenticated, until it
naturally expired — up to 15 minutes after the user explicitly logged out.

**Options considered:**
- Leave it as-is: stateless, zero per-request cost, bounded only by the 15-minute
  access token TTL
- Embed a session id in the access token and check `Session.revoked` in Postgres on
  every authenticated request
- Same check, but backed by a fast cache (Redis) instead of hitting Postgres directly
- Shrink the access token TTL instead of adding any check at all

**Decision:** Add the session id as a JWT claim (`sid`) and check it against
Postgres on every request, for now — with an explicit intent to move the same check
to Redis once that's introduced.

**Why:** A revoked session that still grants access for up to 15 minutes is a real
window, not a theoretical one — long enough to matter if the reason for the logout
was a stolen device or a compromised token. Once we chose to close that window, the
DB check was the immediately available option that didn't require standing up new
infrastructure just to ship this. Shrinking the TTL instead was rejected because it
only narrows the exposure window, it doesn't close it — and it makes every client
refresh far more often for no correctness gain.

**Trade-off we accepted:** This is a direct, explicit walk-back of Decision 1's
core premise — every authenticated request now costs one Postgres query
(`SessionRepository.findById`) before it ever reaches a controller, the exact
per-request database dependency JWTs were originally chosen to avoid. We contained
the damage in one specific way: the check lives entirely inside
`JwtAuthenticationFilter` and `SessionService.isSessionActive()`, so swapping
Postgres for a Redis lookup later is a change to those two spots only — noted
directly in the code as a `TODO` — not a redesign of how tokens work.

---

## Decision 16: Require email verification before an account can log in

**Context:** Registration previously created an `ACTIVE` user immediately — anyone
could register with an email address they don't control (a typo, or someone else's
address) and the account would work right away.

**Options considered:**
- Skip verification: activate accounts immediately at registration (the old
  behavior)
- Require verification: new accounts start `PENDING` and can't log in until they
  click a link proving they control the email address

**Decision:** New users start in `PENDING` status with `emailVerified = false`;
`AuthenticationService.login()` rejects login with `403` until that flips to
verified (see [Decision 17](decisions.md#decision-17-opaque-database-backed-verification-tokens-mirroring-refreshtoken)
for how verification actually happens).

**Why:** An email address is the *only* channel this system has to reach a user —
password resets, security alerts, and (eventually) MFA all assume it's real and
reachable. Registering without proving that costs nothing and catches typos,
squatting, and abuse (mass-registering accounts against addresses you don't own)
before they become a real account with real access. It's also a prerequisite for
the "disabled user" and account-status roadmap items from earlier days — `PENDING`
is now a real, meaningful state in the lifecycle, not just a placeholder.

**Trade-off we accepted:** A brand-new, legitimate user can't log in immediately
after registering — they must find and click the email first. That's a real UX
cost, mitigated by `resend-verification` for the case where the email is lost or
delayed.

---

## Decision 17: Opaque, database-backed verification tokens, mirroring RefreshToken

**Context:** The verification link (`GET /verify?token=...`) needs some way to prove
"this request came from whoever received the email at registration."

**Options considered:**
- A signed, self-contained token (e.g. a short-lived JWT encoding the user id) —
  no database lookup needed to verify it
- An opaque random string, stored in its own `verification_tokens` table, looked up
  by exact value

**Decision:** Opaque token (`VerificationToken` entity), the same shape of decision
already made for refresh tokens in
[Decision 10](decisions.md#decision-10-opaque-database-backed-refresh-tokens-instead-of-a-second-jwt).

**Why:** The same reasoning applies here even more directly than it did for refresh
tokens: a verification token is inherently a **one-time-use, revocable-by-design**
credential — the entire point is that using it once should permanently invalidate
it (see [Decision 19](decisions.md#decision-19-one-time-tokens-delete-on-use-not-just-a-used-flag)).
A self-contained JWT has no row to delete or flag as used — it would stay
cryptographically valid for its full lifetime even after being consumed, which
directly contradicts what "verify your email" is supposed to mean. An opaque,
database-tracked token gives us a real place to enforce single-use.

**Trade-off we accepted:** A database round-trip to verify — completely
acceptable here, since this endpoint is called once per user, not per request, and
was never a candidate for the stateless treatment JWTs get.

---

## Decision 18: Verification tokens expire

**Context:** `VerificationToken.expiresAt` gives every token a deadline
(`verification.token-expiration`, 24 hours) — `verifyEmail()` rejects an otherwise
valid, unused token once that deadline passes.

**Options considered:**
- No expiry: a verification link works forever, however long it sits unused
- A fixed expiry window, after which the token is worthless and a new one must be
  requested via `resend-verification`

**Decision:** 24-hour expiry, enforced in `EmailVerificationService.verifyEmail()`.

**Why:** An email inbox is not a secure vault — links sit in inboxes, get forwarded,
get indexed by mail-provider link scanners, and remain valid for as long as the
token behind them does. An unbounded verification link is a permanent standing
credential for "activate this specific account," discoverable by anyone who
eventually gets access to that inbox, long after the original registration is
irrelevant. A short, bounded window shrinks that exposure to something an attacker
would need to catch in near-real-time, and `cleanupExpiredTokens()` exists
specifically to sweep the debris so expired, useless rows don't pile up forever.

**Trade-off we accepted:** A user who registers and doesn't check their email
within 24 hours has to explicitly request a new link via `resend-verification`
rather than the original one still working — a minor friction cost for a real
reduction in how long a leaked link stays dangerous.

---

## Decision 19: One-time tokens — delete on use, not just a `used` flag

**Context:** `VerificationToken` has both a `used` boolean *and* gets deleted from
the table the moment `verifyEmail()` succeeds — Step 5's flow explicitly lists
"Mark Used" and "Delete Token" as two separate steps, which looks redundant at
first glance (why flag something you're about to delete anyway?).

**Options considered:**
- Delete only: remove the row immediately on successful verification, skip the
  `used` flag entirely
- Flag only: keep the row forever, marked `used = true`, and check that flag on
  every verification attempt
- Both: check `used` as a guard *before* activating the account, then delete the
  row as cleanup in the same transaction

**Decision:** Both, in that order — check `used` (and `expiresAt`) first, only then
activate the user and delete the token.

**Why:** The `used` check is what actually prevents replay — if a verification link
is clicked twice (a mail client prefetching links, a user double-clicking, a link
scanner following it before the real user does), the second attempt must fail
*before* re-running "activate the account" a second time, not rely on the row
already being gone as its only defense. Deleting afterward is what keeps
`verification_tokens` from accumulating a permanent record of every link ever
issued — once a token is spent, there's no future reason to keep it around, unlike
`RefreshToken`'s soft-revoke (Decision 11), which deliberately keeps an audit trail
of the rotation chain for investigating compromised accounts. A verification token
has no equivalent forensic value once used.

**Trade-off we accepted:** None of real consequence — the extra `UPDATE` before the
`DELETE` is negligible cost inside a single transaction, for a real correctness
guarantee against double-processing.

---

## Decision 20: Password reset tokens expire in 1 hour, not 24

**Context:** `PasswordResetProperties.tokenExpiration` gives reset links a much
shorter life than verification links (`VerificationProperties`, 24 hours).

**Options considered:**
- The same 24-hour window as email verification, for consistency
- A shorter, separate window specifically for password reset

**Decision:** 1 hour.

**Why:** The two links grant very different capabilities. A verification link only
ever does one thing — flips a boolean and a status enum — for an account that
isn't usable yet anyway. A password reset link, if intercepted, hands over the
password itself: full account takeover, on an account that's *already active* and
potentially in daily use. The two shouldn't share a risk budget just because they're
both "click a link in an email" — the reset link's blast radius is strictly larger,
so its exposure window is strictly shorter.

**Trade-off we accepted:** A user who doesn't check their inbox within an hour has
to restart the flow via `forgot-password` again — more friction than the 24-hour
verification window, in exchange for a shorter standing window during which a
leaked or intercepted reset link is actually dangerous.

---

## Decision 21: One-time reset tokens

**Context:** Same question as [Decision 19](decisions.md#decision-19-one-time-tokens-delete-on-use-not-just-a-used-flag)
for verification tokens, asked again for password reset: should a reset token stay
valid for repeated use within its expiry window, or die the instant it's used once?

**Options considered:**
- Reusable within the expiry window: the same link works every time until it
  expires
- One-time: `used` is checked before the password change happens, and the token can
  never succeed again afterward — but this time the row is *kept* (not deleted),
  with `usedAt` recorded

**Decision:** One-time, and — unlike verification tokens — the spent row is kept
rather than deleted.

**Why the one-time part:** identical reasoning to Decision 19 — replay protection.
A reset link sitting in an inbox or forwarded somewhere shouldn't be a standing
"change this password whenever" credential; it should work exactly once, for the
one reset it was issued for. **Why keep the row instead of deleting it, unlike
verification tokens:** a spent password-reset token *does* have forensic value a
spent verification token doesn't — `usedAt` becomes a timestamped record of exactly
when a password was changed via this path, which matters if an account compromise
is being investigated later. Deleting it would throw that away for no benefit.

**Trade-off we accepted:** `password_reset_tokens` accumulates rows forever (no
delete, no scheduled cleanup was requested for this table specifically) — a
deliberate accept-and-revisit, not an oversight; the same `cleanupExpiredTokens()`-style
sweep built for verification tokens could be extended here if the table's size
ever becomes a real concern.

---

## Decision 22: The raw reset token is never stored — only its hash

**Context:** `RefreshToken` and `VerificationToken` both store the raw, usable
token value directly and look it up by exact string match. `PasswordResetToken`
stores `tokenHash` instead — a SHA-256 digest — and the raw value only ever exists
in memory long enough to email it once.

**Options considered:**
- Store the raw token directly, exactly like the other two token types
- Store only a one-way hash of it; the raw value is generated, emailed, and then
  forgotten by the application entirely

**Decision:** Hash-only storage (`OpaqueTokenGenerator.hash()`, SHA-256).

**Why:** This is the one token in the system that, if the database itself were ever
read by an attacker (a SQL injection, a backup leak, an insider), would otherwise
hand over a live, working "reset anyone's password" credential for every
outstanding request — worse than leaking a refresh token, because a refresh token
only extends an *already-authenticated* session, while a reset token creates a new
authenticated state from nothing. Hashing the stored value means a database leak
alone is no longer enough to use these tokens — the attacker would need the raw
value, which only ever left the server once, inside the outbound email. On
`reset-password`, we hash the *incoming* raw token with the same function and
compare hashes — SHA-256 is deterministic, so this is a plain lookup, not a
BCrypt-style per-attempt comparison (BCrypt's random salt would make this
lookup-by-hash approach impossible; that's why password hashing and token hashing
use deliberately different algorithms — see
[Decision 2](decisions.md#decision-2-bcrypt-for-password-hashing)).

**Trade-off we accepted:** None, really — hashing before storage costs a few
microseconds and adds real defense-in-depth for the single highest-value token
type in the system. The only reason `RefreshToken`/`VerificationToken` don't do the
same is that neither is quite this sensitive on its own (each still requires a
separately-valid access token or a separate email-ownership check), but this is
arguably the strongest default going forward for any *new* token type, not a
one-off.

---

## Decision 23: Resetting a password revokes every existing session

**Context:** After `reset-password` succeeds, all of that user's existing sessions
and refresh tokens could either be left alone (they were valid before, and the
account holder presumably still controls at least the device they're resetting
from) or revoked entirely.

**Options considered:**
- Leave existing sessions alone — only future logins get the new password
  requirement
- Revoke everything — `RefreshTokenService.logoutAll(userId)`, the identical
  mechanism behind `POST /api/v1/auth/logout-all`

**Decision:** Revoke everything, every time, unconditionally.

**Why:** The most common real-world reason someone resets a password is that they
suspect (or know) the old one is compromised. If an attacker is *already* logged in
with a stolen password when the real owner resets it, leaving that attacker's
session alive would make the reset pointless — they'd keep full access right up
until their access token happened to expire on its own (up to 15 minutes, or
instantly once [Decision 15](decisions.md#decision-15-check-session-revocation-on-every-request-not-just-token-expiry)'s
check catches it on their next request, but only once we revoke — not automatically
just because the password changed). Forcing every device to log in again with the
new password is the only way a reset actually closes the door.

**Trade-off we accepted:** The legitimate user *also* gets logged out of every
device they were using, including the one they just used to complete the reset —
mildly inconvenient, but the alternative (silently leaving a possibly-attacker
session alive) is a real security hole, not a minor UX cost.

---

## Decision 24: User enumeration prevention as one consistent pattern, not four separate ones

**Context:** `forgot-password` is now the fourth endpoint in this codebase facing
the same underlying question: does responding differently for "account exists" vs
"account doesn't exist" leak information worth protecting? (The other three: login
— [Decision 3](decisions.md#decision-3-one-generic-error-for-every-login-failure);
`resend-verification`; and now `forgot-password`.)

**Options considered:**
- Decide enumeration-safety independently, endpoint by endpoint, as each one got
  built
- Treat it as one standing rule applied consistently everywhere a request is keyed
  off an email address the caller doesn't yet own

**Decision:** One rule, applied the same way every time: whenever an endpoint takes
an email address from an unauthenticated caller, the response is identical
regardless of whether that email is registered, verified, or has a pending request
already — `login`, `resend-verification`, and now `forgot-password` all do this.

**Why:** These three endpoints are really the same shape of problem wearing
different clothes — "prove you receive mail at this address before I tell you
anything about the account behind it." Solving it once, as a standing pattern
(silent no-op internally, identical success response externally, checked
consistently at the controller boundary) means every *new* endpoint that takes an
email address inherits the right default just by following the existing examples,
instead of each one needing its own from-scratch security review to notice the
enumeration risk exists at all.

**Trade-off we accepted:** Debugging "why didn't I get my reset email" is
genuinely harder from the outside — the API will never confirm whether the email
you typed was even registered. That's the intended cost: the instant the response
tells a caller "no account with that email," it also tells a *different* caller
"yes, this email is registered," and there's no way to give one answer without
giving the other.
