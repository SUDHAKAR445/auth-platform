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
infrastructure. We mitigated this with a short 15-minute expiry plus a separate
refresh-token flow (see
[Decision 10](decisions.md#decision-10-opaque-database-backed-refresh-tokens-instead-of-a-second-jwt))
that gives us a revocation point the access token itself doesn't have.

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

**Trade-off we accepted:** A live access token can't be revoked server-side before it
naturally expires — the 15-minute expiry is the ceiling on how long that exposure
lasts. "Log out everywhere" (see
[Decision 12](decisions.md#decision-12-session-as-a-separate-entity-from-refreshtoken))
revokes every *refresh* token immediately, so a logged-out user can't get a *new*
access token — but any access token already handed out keeps working until it
expires on its own.

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
