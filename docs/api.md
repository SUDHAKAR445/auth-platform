# API Reference

Base URL: `http://localhost:8081`
Interactive docs: `/swagger-ui/index.html` (raw spec at `/v3/api-docs`)

All error responses share one shape (`ErrorResponse`):

```json
{
  "timestamp": "2026-08-10T10:15:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/v1/auth/register",
  "fieldErrors": {
    "email": "email must be a valid email address"
  }
}
```

`fieldErrors` is only present on validation failures (`400`); every other error omits
it (`ErrorResponse` uses `@JsonInclude(NON_NULL)`).

---

## `POST /api/v1/auth/register`

Creates a new user. Public — no token required.

**Request**

```json
{
  "firstName": "Sudhakar",
  "lastName": "RK",
  "email": "sudhakar@gmail.com",
  "password": "Password@123"
}
```

| Field | Rules |
|---|---|
| `firstName` | required, ≤ 100 chars |
| `lastName` | required, ≤ 100 chars |
| `email` | required, valid email format, ≤ 255 chars |
| `password` | required, ≤ 255 chars, and must satisfy **all** of: ≥ 8 chars, one uppercase, one lowercase, one digit, one special character (`@StrongPassword`) |

**Success response — `201 Created`**

```json
{ "message": "Registration successful. Please verify your email to activate your account." }
```

The new user is created with `status: PENDING` and `emailVerified: false` — they
cannot log in until they complete email verification (see
[decisions.md](decisions.md#decision-16-require-email-verification-before-an-account-can-log-in)).
Registration also triggers `POST`-equivalent side effects: a `VerificationToken` row
is created and `EmailService.sendVerificationEmail()` prints a verification link to
the console (see `GET /api/v1/auth/verify` below).

**Error responses**

| Status | Cause | Body |
|---|---|---|
| `400` | one or more fields fail validation | `ErrorResponse` with `fieldErrors` (first violated rule per field) |
| `409` | email already registered | `ErrorResponse`, message: `A user with email '...' already exists` |

Password is never stored or returned in plaintext — it's hashed with BCrypt before the
row is saved (see [decisions.md](decisions.md#decision-2-bcrypt-for-password-hashing)).

---

## `POST /api/v1/auth/login`

Authenticates a user and issues a JWT. Public — no token required.

**Request**

```json
{
  "email": "sudhakar@gmail.com",
  "password": "Password@123"
}
```

| Field | Rules |
|---|---|
| `email` | required, valid email format |
| `password` | required (non-blank only — strength rules don't apply to login) |
| `deviceId` | optional — ties the issued refresh token to this device; omit if the client doesn't track devices |

**Success response — `200 OK`**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "refreshToken": "5kQ3f1z...opaque-string...",
  "expiresIn": 900
}
```

`expiresIn` describes only the **access** token, in seconds (900 = 15 minutes). Send
the access token on subsequent requests as `Authorization: Bearer <accessToken>`.
When it expires, use `refreshToken` against `POST /api/v1/auth/refresh` below to get
a new pair — the refresh token itself is valid for 7 days
(`jwt.refresh-expiration`), and unlike the access token it's a random opaque string,
not a JWT (see [decisions.md](decisions.md#decision-10-opaque-database-backed-refresh-tokens-instead-of-a-second-jwt)).

**Error responses**

| Status | Cause | Body |
|---|---|---|
| `400` | missing/blank email or password | `ErrorResponse` with `fieldErrors` |
| `401` | email not found, OR wrong password, OR account status ≠ `ACTIVE` | `ErrorResponse`, message: `Invalid email or password` (deliberately identical for all three causes — see [decisions.md](decisions.md#decision-3-one-generic-error-for-every-login-failure)) |
| `403` | password is correct, but `emailVerified` is still `false` | `ErrorResponse`, message: `Please verify your email before logging in` — see [decisions.md](decisions.md#decision-16-require-email-verification-before-an-account-can-log-in) |

---

## `POST /api/v1/auth/refresh`

Exchanges a valid refresh token for a new access token *and* a new refresh token.
Public — no access token required (the whole point is the caller's access token has
expired).

**Request**

```json
{ "refreshToken": "5kQ3f1z...opaque-string..." }
```

| Field | Rules |
|---|---|
| `refreshToken` | required |

**Success response — `200 OK`**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...(new)",
  "refreshToken": "aB9xQ2p...(new, opaque)"
}
```

The refresh token you sent is now **revoked** — using it again fails, even though it
hasn't expired yet (see
[decisions.md](decisions.md#decision-11-rotate-and-revoke-on-every-refresh-never-reuse-a-refresh-token)).
Always store the new `refreshToken` from the response and discard the old one.

**Error responses**

| Status | Cause | Body |
|---|---|---|
| `400` | missing/blank `refreshToken` | `ErrorResponse` with `fieldErrors` |
| `401` | token not found, already revoked (including reuse after rotation), or expired | `ErrorResponse`, message: `Invalid or expired refresh token` |

---

## `POST /api/v1/auth/logout`

Revokes a single refresh token and deletes its session. Public — no access token
required (see [decisions.md](decisions.md#decision-14-logout-is-public-logout-all-requires-a-valid-access-token)).

**Request**

```json
{ "refreshToken": "5kQ3f1z...opaque-string..." }
```

| Field | Rules |
|---|---|
| `refreshToken` | required |

**Success response — `204 No Content`** (empty body)

After this call, the refresh token you sent can no longer be used at
`POST /api/v1/auth/refresh` — and its session no longer appears in
`GET /api/v1/sessions`.

**Error responses**

| Status | Cause | Body |
|---|---|---|
| `400` | missing/blank `refreshToken` | `ErrorResponse` with `fieldErrors` |
| `401` | token not found, already revoked, or expired | `ErrorResponse`, message: `Invalid or expired refresh token` |

---

## `POST /api/v1/auth/logout-all`

Revokes **every** refresh token and deletes **every** session belonging to the
authenticated user — "log out of all devices." **Requires** a valid JWT
(`Authorization: Bearer <token>`). No request body.

**Success response — `204 No Content`** (empty body)

Every access token already issued keeps working until it naturally expires (up to
15 minutes) — only the ability to get a *new* one via `/refresh` is cut off
immediately. See [decisions.md](decisions.md#decision-4-stateless-authentication).

**Error responses**

| Status | Cause | Body |
|---|---|---|
| `401` | no token, malformed token, or expired token | (no body — rejected by `SecurityConfig` before reaching the controller) |

---

## `GET /api/v1/auth/verify`

Activates a pending account. Public — the caller isn't logged in yet by definition.

**Request** — query parameter, not a body:

```
GET /api/v1/auth/verify?token=5kQ3f1z...opaque-string...
```

| Parameter | Rules |
|---|---|
| `token` | required |

**Success response — `200 OK`**

```json
{ "message": "Email verified successfully" }
```

After this call, the user's `status` becomes `ACTIVE` and `emailVerified` becomes
`true` — they can now log in. The token itself is deleted and cannot be used again,
even if it hasn't expired (see
[decisions.md](decisions.md#decision-19-one-time-tokens-delete-on-use-not-just-a-used-flag)).

**Error responses**

| Status | Cause | Body |
|---|---|---|
| `400` | token not found, already used, or expired (24-hour window, see [decisions.md](decisions.md#decision-18-verification-tokens-expire)) | `ErrorResponse`, message: `Invalid or expired verification token` |

---

## `POST /api/v1/auth/resend-verification`

Issues a new verification token and email for an account that hasn't verified yet.
Public.

**Request**

```json
{ "email": "user@gmail.com" }
```

| Field | Rules |
|---|---|
| `email` | required, valid email format |

**Success response — `200 OK`**, always the same regardless of what actually happened:

```json
{ "message": "If an account exists for this email and isn't verified yet, a verification email has been sent." }
```

This response is intentionally identical whether the email doesn't exist, is
already verified, or genuinely got a new token — same enumeration-prevention
reasoning as login (see
[decisions.md](decisions.md#decision-3-one-generic-error-for-every-login-failure)).
There is no error response that reveals which case occurred; a malformed email
still gets a `400` for the field validation itself.

**Error responses**

| Status | Cause | Body |
|---|---|---|
| `400` | missing/blank or malformed `email` | `ErrorResponse` with `fieldErrors` |

---

## `GET /api/v1/sessions`

Lists the authenticated user's active (non-revoked, non-expired) sessions —
one entry per logged-in device. **Requires** a valid JWT.

**Success response — `200 OK`**

```json
[
  { "device": "Chrome", "ip": "192.168.1.1", "lastUsed": "2026-08-05T10:00:00Z" },
  { "device": "Android", "ip": "192.168.1.20", "lastUsed": "2026-08-05T09:30:00Z" }
]
```

`device` is parsed from the request's `User-Agent` header at login time (a small
heuristic — recognizes `Android`, `iPhone`, `iPad`, `Chrome`, `Firefox`, `Safari`,
`Edge`; falls back to `"Unknown"`). `lastUsed` updates every time that session's
refresh token is rotated via `/refresh`.

**Error responses**

| Status | Cause | Body |
|---|---|---|
| `401` | no token, malformed token, or expired token | (no body — rejected by `SecurityConfig` before reaching the controller) |

---

## `GET /api/v1/users/{id}`

Fetches a user's profile. **Requires** a valid JWT (`Authorization: Bearer <token>`).

**Success response — `200 OK`**

```json
{
  "id": 1,
  "firstName": "Sudhakar",
  "lastName": "RK",
  "email": "sudhakar@gmail.com",
  "status": "ACTIVE",
  "emailVerified": false,
  "createdAt": "2026-08-10T10:00:00Z",
  "updatedAt": "2026-08-10T10:00:00Z"
}
```

Never includes the password hash.

**Error responses**

| Status | Cause | Body |
|---|---|---|
| `401` | no token, malformed token, or expired token | (no body — rejected by `SecurityConfig` before reaching the controller) |
| `404` | no user with that id | `ErrorResponse`, message: `User not found with id: {id}` |

---

## Status code summary

| Code | Meaning here |
|---|---|
| `200` | login succeeded / user found / active sessions listed |
| `201` | user registered |
| `204` | logout / logout-all succeeded (no body to return) |
| `400` | request body failed Bean Validation, or an invalid/expired/used verification token |
| `401` | missing/invalid JWT, invalid login credentials, invalid/expired/revoked refresh token, or a valid-but-revoked session (see [decisions.md](decisions.md#decision-15-check-session-revocation-on-every-request-not-just-token-expiry)) |
| `403` | login attempted before email verification |
| `404` | user id doesn't exist |
| `409` | email already registered |
| `500` | unhandled server error (logged, generic message returned — internals never leak to the client) |
