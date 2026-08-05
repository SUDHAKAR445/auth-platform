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
{ "message": "Registration Successful" }
```

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
| `200` | login succeeded / user found |
| `201` | user registered |
| `400` | request body failed Bean Validation |
| `401` | missing/invalid JWT, invalid login credentials, or invalid/expired/revoked refresh token |
| `404` | user id doesn't exist |
| `409` | email already registered |
| `500` | unhandled server error (logged, generic message returned — internals never leak to the client) |
