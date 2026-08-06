# Architecture

`auth-platform` is a multi-module Maven project. Only `auth-service` has real code so
far; `common`, `user-service`, `notification`, and `gateway` are module skeletons
reserved for future work (see [decisions.md](decisions.md#decision-6-multi-module-maven-layout-from-day-one)).

## Component diagram

```mermaid
flowchart TB
    Client(["Client (curl / Postman / Swagger UI)"])

    subgraph AuthService["auth-service"]
        direction TB
        SecurityFilterChain["Spring Security filter chain<br/>(JwtAuthenticationFilter)"]

        subgraph Controllers["controller/"]
            RegCtrl[RegistrationController]
            LoginCtrl[LoginController]
            RefreshCtrl[RefreshTokenController]
            LogoutCtrl[LogoutController]
            SessionCtrl[SessionController]
            UserCtrl[UserController]
        end

        subgraph Services["service/"]
            RegSvc[RegistrationService]
            AuthSvc[AuthenticationService]
            RefreshSvc[RefreshTokenService]
            SessionSvc[SessionService]
            UserSvc[UserService]
        end

        subgraph SecurityPkg["security/"]
            JwtSvc[JwtService]
            PwEncoder[PasswordEncoder - BCrypt]
        end

        Repo[UserRepository]
        RefreshRepo[RefreshTokenRepository]
        SessionRepo[SessionRepository]
        GEH[GlobalExceptionHandler]
    end

    DB[("PostgreSQL<br/>auth_platform.users /<br/>refresh_tokens / sessions")]

    Client --> SecurityFilterChain
    SecurityFilterChain --> RegCtrl
    SecurityFilterChain --> LoginCtrl
    SecurityFilterChain --> RefreshCtrl
    SecurityFilterChain --> LogoutCtrl
    SecurityFilterChain --> SessionCtrl
    SecurityFilterChain --> UserCtrl

    RegCtrl --> RegSvc
    LoginCtrl --> AuthSvc
    RefreshCtrl --> RefreshSvc
    LogoutCtrl --> RefreshSvc
    LogoutCtrl --> Repo
    SessionCtrl --> SessionSvc
    SessionCtrl --> Repo
    UserCtrl --> UserSvc

    RegSvc --> PwEncoder
    RegSvc --> Repo
    AuthSvc --> PwEncoder
    AuthSvc --> JwtSvc
    AuthSvc --> Repo
    AuthSvc --> RefreshSvc
    RefreshSvc --> JwtSvc
    RefreshSvc --> Repo
    RefreshSvc --> RefreshRepo
    RefreshSvc --> SessionSvc
    SessionSvc --> SessionRepo
    UserSvc --> Repo

    SecurityFilterChain -.uses.-> JwtSvc
    Repo --> DB
    RefreshRepo --> DB
    SessionRepo --> DB

    RegCtrl -.exceptions.-> GEH
    LoginCtrl -.exceptions.-> GEH
    RefreshCtrl -.exceptions.-> GEH
    LogoutCtrl -.exceptions.-> GEH
    SessionCtrl -.exceptions.-> GEH
    UserCtrl -.exceptions.-> GEH
```

Note the one-way arrow `RefreshSvc --> SessionSvc`, not the reverse — `SessionService`
never depends on `RefreshTokenService`. That's deliberate; see
[decisions.md](decisions.md#decision-13-one-service-owns-both-refreshtoken-and-session-to-avoid-a-circular-dependency)
for why the two would otherwise form a circular bean dependency.

**Why three services instead of one `UserService`:** registration, authentication, and
profile lookup are different responsibilities with different reasons to change —
splitting them keeps each class focused and testable in isolation. See
[decisions.md](decisions.md#decision-7-split-registration-authentication-and-profile-lookup-into-separate-services).

## Request flow — registration

```mermaid
sequenceDiagram
    participant C as Client
    participant Ctrl as RegistrationController
    participant Svc as RegistrationService
    participant Enc as PasswordEncoder
    participant Repo as UserRepository
    participant DB as PostgreSQL

    C->>Ctrl: POST /api/v1/auth/register
    Ctrl->>Ctrl: @Valid — firstName/lastName/email/password rules
    Ctrl->>Svc: registerUser(request)
    Svc->>Repo: existsByEmail(email)
    Repo->>DB: SELECT
    DB-->>Repo: false
    Svc->>Enc: encode(rawPassword)
    Enc-->>Svc: bcryptHash
    Svc->>Repo: save(user)
    Repo->>DB: INSERT
    Svc-->>Ctrl: RegisterResponse("Registration Successful")
    Ctrl-->>C: 201 Created
```

## Request flow — login

```mermaid
sequenceDiagram
    participant C as Client
    participant Ctrl as LoginController
    participant Svc as AuthenticationService
    participant Repo as UserRepository
    participant Enc as PasswordEncoder
    participant Jwt as JwtService
    participant RSvc as RefreshTokenService
    participant SSvc as SessionService

    C->>Ctrl: POST /api/v1/auth/login<br/>(Ctrl reads IP + User-Agent)
    Ctrl->>Svc: login(request, ipAddress, userAgent)
    Svc->>Repo: findByEmail(email)
    alt not found
        Svc-->>Ctrl: throw InvalidCredentialsException
        Ctrl-->>C: 401
    else found
        Svc->>Enc: matches(rawPassword, storedHash)
        alt wrong password OR status != ACTIVE
            Svc-->>Ctrl: throw InvalidCredentialsException
            Ctrl-->>C: 401
        else valid
            Svc->>Jwt: generateToken(email)
            Jwt-->>Svc: signed JWT
            Svc->>RSvc: generateRefreshToken(user, deviceId, ip, userAgent)
            RSvc->>RSvc: save RefreshToken
            RSvc->>SSvc: createSession(userId, token, expiresAt, ip, userAgent)
            SSvc-->>RSvc: Session saved (device parsed from User-Agent)
            RSvc-->>Svc: opaque refresh token
            Svc-->>Ctrl: LoginResponse(accessToken, "Bearer", refreshToken, 900)
            Ctrl-->>C: 200 OK
        end
    end
```

Every failure branch above throws the *same* `InvalidCredentialsException` with the
*same* message — see
[decisions.md](decisions.md#decision-3-one-generic-error-for-every-login-failure).

## Request flow — refresh token rotation

```mermaid
sequenceDiagram
    participant C as Client
    participant Ctrl as RefreshTokenController
    participant Svc as RefreshTokenService
    participant Repo as RefreshTokenRepository
    participant URepo as UserRepository
    participant Jwt as JwtService
    participant SSvc as SessionService

    C->>Ctrl: POST /api/v1/auth/refresh
    Ctrl->>Svc: rotateRefreshToken(request)
    Svc->>Repo: findByToken(refreshToken)
    alt not found, revoked, or expired
        Svc-->>Ctrl: throw InvalidRefreshTokenException
        Ctrl-->>C: 401
    else valid
        Svc->>URepo: findById(oldToken.userId)
        Svc->>Jwt: generateToken(user.email)
        Jwt-->>Svc: new access token
        Svc->>Repo: save(new RefreshToken, same deviceId)
        Svc->>Repo: save(oldToken, revoked=true)
        Svc->>SSvc: updateLastUsed(oldToken, newToken, newExpiresAt)
        SSvc->>SSvc: find session by old token, swap in new token + expiry, bump lastUsed
        Svc-->>Ctrl: RefreshTokenResponse(newAccessToken, newRefreshToken)
        Ctrl-->>C: 200 OK
    end
```

The old refresh token is never deleted, only flagged `revoked = true` — reusing it
after rotation fails the same way an unknown token would. See
[decisions.md](decisions.md#decision-11-rotate-and-revoke-on-every-refresh-never-reuse-a-refresh-token).
Note the session row is *updated in place*, not duplicated — one row per device
persists across every rotation until logout.

## Request flow — logout and logout-all

```mermaid
sequenceDiagram
    participant C as Client
    participant LCtrl as LogoutController
    participant RSvc as RefreshTokenService
    participant SSvc as SessionService

    rect rgb(40,40,40)
    note over C,SSvc: POST /api/v1/auth/logout — public, refresh token proves intent
    C->>LCtrl: POST /logout {refreshToken}
    LCtrl->>RSvc: logout(refreshToken)
    RSvc->>RSvc: validateRefreshToken() then revoke it
    RSvc->>SSvc: deleteByRefreshToken(refreshToken)
    RSvc-->>LCtrl: done
    LCtrl-->>C: 204 No Content
    end

    rect rgb(40,40,40)
    note over C,SSvc: POST /api/v1/auth/logout-all — requires a valid JWT
    C->>LCtrl: POST /logout-all<br/>Authorization: Bearer <token>
    LCtrl->>RSvc: logoutAll(userId from JWT)
    RSvc->>RSvc: revoke every RefreshToken for userId
    RSvc->>SSvc: revokeAndDeleteAllForUser(userId)
    RSvc-->>LCtrl: done
    LCtrl-->>C: 204 No Content
    end
```

See [decisions.md](decisions.md#decision-14-logout-is-public-logout-all-requires-a-valid-access-token)
for why these two have different access rules.

## Security flow — JWT on a protected request

```mermaid
sequenceDiagram
    participant C as Client
    participant Filter as JwtAuthenticationFilter
    participant Jwt as JwtService
    participant SCH as SecurityContextHolder
    participant Ctrl as UserController

    C->>Filter: GET /api/v1/users/1<br/>Authorization: Bearer <token>
    Filter->>Filter: extract token from header
    Filter->>Jwt: validateToken(token)
    alt invalid / expired / missing
        Jwt-->>Filter: false
        Filter->>Ctrl: continue filter chain, no auth set
        Ctrl-->>C: 401 (rejected by authorizeHttpRequests)
    else valid
        Jwt-->>Filter: true
        Filter->>Jwt: extractUsername(token)
        Jwt-->>Filter: email
        Filter->>SCH: setAuthentication(email, authorities=[])
        Filter->>Ctrl: continue filter chain, authenticated
        Ctrl-->>C: 200 OK
    end
```

**Route rules** (`SecurityConfig`):

| Route | Rule |
|---|---|
| `POST /api/v1/auth/register` | public |
| `POST /api/v1/auth/login` | public |
| `POST /api/v1/auth/refresh` | public (the caller has no valid access token by definition) |
| `POST /api/v1/auth/logout` | public (a valid refresh token is sufficient proof of intent) |
| `POST /api/v1/auth/logout-all` | requires a valid JWT — see [decisions.md](decisions.md#decision-14-logout-is-public-logout-all-requires-a-valid-access-token) |
| `GET /api/v1/sessions` | requires a valid JWT |
| `/swagger-ui/**`, `/v3/api-docs/**` | public (dev tooling only) |
| everything else | requires a valid JWT |

Session creation policy is `STATELESS` — no server-side session is ever created; the
JWT is the only proof of identity on each request. See
[decisions.md](decisions.md#decision-4-stateless-authentication).
