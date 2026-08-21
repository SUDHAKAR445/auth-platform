# Architecture

`auth-platform` is a multi-module Maven project. Only `auth-service` has real code so
far; `common`, `user-service`, `notification`, and `gateway` are module skeletons
reserved for future work (see [decisions.md](decisions.md#decision-6-multi-module-maven-layout-from-day-one)).

## Logical domain view

This is the conceptual shape of the platform — how its *capabilities* group by
responsibility, independent of how the Java code is physically packaged today:

```mermaid
flowchart TB
    IP[Identity Platform]

    IP --> Auth[Authentication]
    IP --> Iden[Identity]
    IP --> Sess[Session]
    IP --> Notif[Notification]

    Auth --> AuthLogin[Login]
    Auth --> AuthJwt[JWT]
    Auth --> AuthRefresh[Refresh]

    Iden --> IdenReg[Register]
    Iden --> IdenVerify[Verify Email]
    Iden --> IdenReset[Reset Password]

    Sess --> SessDevice[Device Session]
    Sess --> SessLogout[Logout]
    Sess --> SessLogoutAll[Logout All]

    Notif --> NotifEmail[Email]
    NotifEmail --> NotifVerifyMail[Verification]
    NotifEmail --> NotifResetMail[Password Reset]
```

**This is a logical view, not the physical package structure.** The actual Java
code stays organized by technical layer (`controller/`, `service/`, `repository/`,
etc. — see the component diagram below), not by these domain folders. Repackaging
a 40+ file codebase into `auth/identity/session/notification/` purely for
organizational purposes, mid-feature, was judged not worth the churn each time this
came up (JWT day, the identity-module day, and again here) — but the logical
grouping above is real and worth documenting on its own, since it's how the
*capabilities* actually relate to each other regardless of which folder each class
lives in.

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
            VerifyCtrl[EmailVerificationController]
            ResetCtrl[PasswordResetController]
            UserCtrl[UserController]
        end

        subgraph Services["service/"]
            RegSvc[RegistrationService]
            AuthSvc[AuthenticationService]
            RefreshSvc[RefreshTokenService]
            SessionSvc[SessionService]
            VerifySvc[EmailVerificationService]
            ResetSvc[PasswordResetService]
            MailSvc[EmailService]
            UserSvc[UserService]
        end

        subgraph SecurityPkg["security/"]
            JwtSvc[JwtService]
            PwEncoder[PasswordEncoder - BCrypt]
            TokenGen[OpaqueTokenGenerator]
        end

        Repo[UserRepository]
        RefreshRepo[RefreshTokenRepository]
        SessionRepo[SessionRepository]
        VerifyRepo[VerificationTokenRepository]
        ResetRepo[PasswordResetTokenRepository]
        GEH[GlobalExceptionHandler]
    end

    DB[("PostgreSQL<br/>auth_platform.users /<br/>refresh_tokens / sessions /<br/>verification_tokens / password_reset_tokens")]

    Client --> SecurityFilterChain
    SecurityFilterChain --> RegCtrl
    SecurityFilterChain --> LoginCtrl
    SecurityFilterChain --> RefreshCtrl
    SecurityFilterChain --> LogoutCtrl
    SecurityFilterChain --> SessionCtrl
    SecurityFilterChain --> VerifyCtrl
    SecurityFilterChain --> ResetCtrl
    SecurityFilterChain --> UserCtrl

    RegCtrl --> RegSvc
    LoginCtrl --> AuthSvc
    RefreshCtrl --> RefreshSvc
    LogoutCtrl --> RefreshSvc
    LogoutCtrl --> Repo
    SessionCtrl --> SessionSvc
    SessionCtrl --> Repo
    VerifyCtrl --> VerifySvc
    ResetCtrl --> ResetSvc
    UserCtrl --> UserSvc

    RegSvc --> PwEncoder
    RegSvc --> Repo
    RegSvc --> VerifySvc
    RegSvc --> MailSvc
    AuthSvc --> PwEncoder
    AuthSvc --> JwtSvc
    AuthSvc --> Repo
    AuthSvc --> RefreshSvc
    RefreshSvc --> JwtSvc
    RefreshSvc --> Repo
    RefreshSvc --> RefreshRepo
    RefreshSvc --> SessionSvc
    RefreshSvc --> TokenGen
    SessionSvc --> SessionRepo
    VerifySvc --> Repo
    VerifySvc --> VerifyRepo
    VerifySvc --> MailSvc
    VerifySvc --> TokenGen
    ResetSvc --> Repo
    ResetSvc --> ResetRepo
    ResetSvc --> PwEncoder
    ResetSvc --> MailSvc
    ResetSvc --> TokenGen
    ResetSvc --> RefreshSvc
    UserSvc --> Repo

    SecurityFilterChain -.uses.-> JwtSvc
    SecurityFilterChain -.uses.-> SessionSvc
    Repo --> DB
    RefreshRepo --> DB
    SessionRepo --> DB
    VerifyRepo --> DB
    ResetRepo --> DB

    RegCtrl -.exceptions.-> GEH
    LoginCtrl -.exceptions.-> GEH
    RefreshCtrl -.exceptions.-> GEH
    LogoutCtrl -.exceptions.-> GEH
    SessionCtrl -.exceptions.-> GEH
    VerifyCtrl -.exceptions.-> GEH
    ResetCtrl -.exceptions.-> GEH
    UserCtrl -.exceptions.-> GEH
```

Note the one-way arrow `RefreshSvc --> SessionSvc`, not the reverse — `SessionService`
never depends on `RefreshTokenService`. That's deliberate; see
[decisions.md](decisions.md#decision-13-one-service-owns-both-refreshtoken-and-session-to-avoid-a-circular-dependency)
for why the two would otherwise form a circular bean dependency. Note also
`SecurityFilterChain -.uses.-> SessionSvc` — every authenticated request now checks
session state directly from the filter chain, before a request ever reaches a
controller; see
[decisions.md](decisions.md#decision-15-check-session-revocation-on-every-request-not-just-token-expiry).

`OpaqueTokenGenerator` is shared by `RefreshTokenService`, `EmailVerificationService`,
and now `PasswordResetService` — all three needed the identical "cryptographically
random, URL-safe string" logic, so it was extracted rather than duplicated. It also
now carries a `hash()` method used only by `PasswordResetService` — see
[decisions.md](decisions.md#decision-22-the-raw-reset-token-is-never-stored-only-its-hash)
for why password reset tokens are hashed before storage while the other two token
types aren't.

`PasswordResetService --> RefreshSvc` is another intentional cross-service call,
alongside the `RefreshSvc --> SessionSvc` one above: completing a password reset
calls `RefreshTokenService.logoutAll()` to revoke every session, the same mechanism
`POST /api/v1/auth/logout-all` uses. See
[decisions.md](decisions.md#decision-23-resetting-a-password-revokes-every-existing-session).

**Why three services instead of one `UserService`:** registration, authentication, and
profile lookup are different responsibilities with different reasons to change —
splitting them keeps each class focused and testable in isolation. See
[decisions.md](decisions.md#decision-7-split-registration-authentication-and-profile-lookup-into-separate-services).

## Account lifecycle — registration through activation

```mermaid
flowchart LR
    A[Register] --> B[Save User<br/>status=PENDING]
    B --> C[Generate<br/>Verification Token]
    C --> D[Send Email<br/>console-logged link, for now]
    D --> E{User clicks link}
    E -->|GET /verify| F[Activate User<br/>status=ACTIVE, emailVerified=true]
    E -->|link lost / expired| G[POST /resend-verification]
    G --> C
```

A user created by registration cannot log in (`403`, see
[decisions.md](decisions.md#decision-16-require-email-verification-before-an-account-can-log-in))
until they complete the right-hand path.

## Request flow — registration

```mermaid
sequenceDiagram
    participant C as Client
    participant Ctrl as RegistrationController
    participant Svc as RegistrationService
    participant Enc as PasswordEncoder
    participant Repo as UserRepository
    participant VSvc as EmailVerificationService
    participant Mail as EmailService
    participant DB as PostgreSQL

    C->>Ctrl: POST /api/v1/auth/register
    Ctrl->>Ctrl: @Valid — firstName/lastName/email/password rules
    Ctrl->>Svc: registerUser(request)
    Svc->>Repo: existsByEmail(email)
    Repo->>DB: SELECT
    DB-->>Repo: false
    Svc->>Enc: encode(rawPassword)
    Enc-->>Svc: bcryptHash
    Svc->>Repo: save(user, status=PENDING)
    Repo->>DB: INSERT
    Svc->>VSvc: generateVerificationToken(user)
    VSvc->>DB: INSERT verification_tokens
    VSvc-->>Svc: VerificationToken
    Svc->>Mail: sendVerificationEmail(email, token)
    Mail->>Mail: System.out.println(verification link)
    Svc-->>Ctrl: RegisterResponse("...please verify your email...")
    Ctrl-->>C: 201 Created
```

## Request flow — email verification

```mermaid
sequenceDiagram
    participant C as Client
    participant Ctrl as EmailVerificationController
    participant Svc as EmailVerificationService
    participant VRepo as VerificationTokenRepository
    participant Repo as UserRepository

    C->>Ctrl: GET /api/v1/auth/verify?token=xxxx
    Ctrl->>Svc: verifyEmail(token)
    Svc->>VRepo: findByToken(token)
    alt not found, expired, or already used
        Svc-->>Ctrl: throw InvalidVerificationTokenException
        Ctrl-->>C: 400
    else valid and unused
        Svc->>Repo: activate user (emailVerified=true, status=ACTIVE)
        Svc->>VRepo: mark used=true, then delete the token
        Svc-->>Ctrl: done
        Ctrl-->>C: 200 "Email verified successfully"
    end
```

See [decisions.md](decisions.md#decision-19-one-time-tokens-delete-on-use-not-just-a-used-flag)
for why the token is both flagged used *and* deleted, rather than just one or the
other.

`POST /api/v1/auth/resend-verification` follows the same enumeration-safe pattern as
login (Decision 3): it always returns the same generic message, whether the email
doesn't exist, is already verified, or genuinely gets a new token sent — the
internal branching happens in `EmailVerificationService.resendVerification()`, but
nothing about the outward response reveals which case occurred.

## Account lifecycle — forgot password through reset

```mermaid
flowchart LR
    A[Forgot Password] --> B[Generate Secure<br/>Random Token]
    B --> C[Hash Token<br/>SHA-256]
    C --> D[Store Hash]
    D --> E[Send Raw Token<br/>by Email]
    E --> F{User clicks link}
    F -->|POST /reset-password| G[Reset Password]
    G --> H[Invalidate Sessions]
```

The raw token exists only twice: once when generated, once inside the outbound
email. Only its hash ever touches the database — see
[decisions.md](decisions.md#decision-22-the-raw-reset-token-is-never-stored-only-its-hash).
`H` — invalidating sessions — is not optional cleanup; it's the step that actually
makes the reset meaningful if the account was compromised. See
[decisions.md](decisions.md#decision-23-resetting-a-password-revokes-every-existing-session).

## Request flow — forgot password and reset password

```mermaid
sequenceDiagram
    participant C as Client
    participant Ctrl as PasswordResetController
    participant Svc as PasswordResetService
    participant Repo as UserRepository
    participant RRepo as PasswordResetTokenRepository
    participant Gen as OpaqueTokenGenerator
    participant Mail as EmailService
    participant Enc as PasswordEncoder
    participant RSvc as RefreshTokenService

    rect rgb(40,40,40)
    note over C,Mail: POST /api/v1/auth/forgot-password — public, always the same response
    C->>Ctrl: POST /forgot-password {email}
    Ctrl->>Svc: requestPasswordReset(email)
    Svc->>Repo: findByEmail(email)
    alt not found
        Svc-->>Ctrl: silent no-op
    else found
        Svc->>RRepo: invalidateTokensByUserId(userId)
        Svc->>Gen: generate() then hash(rawToken)
        Svc->>RRepo: save(userId, tokenHash, expiresAt=+1h)
        Svc->>Mail: sendPasswordResetEmail(email, rawToken)
        Mail->>Mail: System.out.println(reset link)
    end
    Ctrl-->>C: 200 "If an account exists, a password reset link has been sent."
    end

    rect rgb(40,40,40)
    note over C,RSvc: POST /api/v1/auth/reset-password — public
    C->>Ctrl: POST /reset-password {token, newPassword}
    Ctrl->>Svc: resetPassword(token, newPassword)
    Svc->>Gen: hash(token)
    Svc->>RRepo: findByTokenHash(hash)
    alt not found, used, or expired
        Svc-->>Ctrl: throw InvalidPasswordResetTokenException
        Ctrl-->>C: 400
    else valid
        Svc->>Enc: encode(newPassword)
        Svc->>Repo: save user with new password hash
        Svc->>RRepo: mark used=true, usedAt=now (row kept, not deleted)
        Svc->>RSvc: logoutAll(userId)
        RSvc->>RSvc: revoke every RefreshToken + Session for userId
        Svc-->>Ctrl: done
        Ctrl-->>C: 200 "Password reset successful. Please log in again."
    end
    end
```

Both endpoints are public — a user requesting a reset has, by definition, no valid
session to authenticate with. See
[decisions.md](decisions.md#decision-24-user-enumeration-prevention-as-one-consistent-pattern-not-four-separate-ones)
for why `forgot-password`'s response never reveals whether the email was registered.

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
        alt wrong password
            Svc-->>Ctrl: throw InvalidCredentialsException
            Ctrl-->>C: 401
        else password OK, but emailVerified == false
            Svc-->>Ctrl: throw EmailNotVerifiedException
            Ctrl-->>C: 403 "Verify Email First"
        else password OK, but status != ACTIVE
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

The wrong-password and inactive-status branches both throw the *same*
`InvalidCredentialsException` with the *same* message — see
[decisions.md](decisions.md#decision-3-one-generic-error-for-every-login-failure).
The email-not-verified branch is the **one deliberate exception** to that rule: it
throws a distinct `EmailNotVerifiedException` with a specific, actionable message.
This does technically confirm the email is registered (a minor enumeration signal
Decision 3 was designed to avoid) — accepted because "go check your email" is
near-universal, low-risk UX across real-world auth systems, and the alternative
(a confused user with no idea why login just silently fails) is worse. See
[decisions.md](decisions.md#decision-16-require-email-verification-before-an-account-can-log-in).

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
    participant SSvc as SessionService
    participant SCH as SecurityContextHolder
    participant Ctrl as UserController

    C->>Filter: GET /api/v1/users/1<br/>Authorization: Bearer <token>
    Filter->>Filter: extract token from header
    Filter->>Jwt: validateToken(token)
    alt invalid / expired / missing signature
        Jwt-->>Filter: false
        Filter->>Ctrl: continue filter chain, no auth set
        Ctrl-->>C: 401 (rejected by authorizeHttpRequests)
    else signature + expiry OK
        Jwt-->>Filter: true
        Filter->>Jwt: extractSessionId(token)
        Jwt-->>Filter: sid claim
        Filter->>SSvc: isSessionActive(sessionId)
        alt session revoked or expired
            SSvc-->>Filter: false
            Filter->>Ctrl: continue filter chain, no auth set
            Ctrl-->>C: 401 (rejected by authorizeHttpRequests)
        else session active
            SSvc-->>Filter: true
            Filter->>Jwt: extractUsername(token)
            Jwt-->>Filter: email
            Filter->>SCH: setAuthentication(email, authorities=[])
            Filter->>Ctrl: continue filter chain, authenticated
            Ctrl-->>C: 200 OK
        end
    end
```

This is the mechanism behind Decision 15 — a token can pass signature and expiry
checks perfectly and *still* get rejected if its session was revoked (logout,
logout-all) since it was issued. It's why every authenticated request now costs one
extra Postgres query before reaching a controller.

**Route rules** (`SecurityConfig`):

| Route | Rule |
|---|---|
| `POST /api/v1/auth/register` | public |
| `POST /api/v1/auth/login` | public |
| `POST /api/v1/auth/refresh` | public (the caller has no valid access token by definition) |
| `POST /api/v1/auth/logout` | public (a valid refresh token is sufficient proof of intent) |
| `POST /api/v1/auth/logout-all` | requires a valid JWT — see [decisions.md](decisions.md#decision-14-logout-is-public-logout-all-requires-a-valid-access-token) |
| `GET /api/v1/auth/verify` | public (the caller isn't logged in yet by definition) |
| `POST /api/v1/auth/resend-verification` | public |
| `POST /api/v1/auth/forgot-password` | public |
| `POST /api/v1/auth/reset-password` | public |
| `GET /api/v1/sessions` | requires a valid JWT |
| `/swagger-ui/**`, `/v3/api-docs/**` | public (dev tooling only) |
| everything else | requires a valid JWT |

Session creation policy is `STATELESS` — no server-side session is ever created; the
JWT is the only proof of identity on each request. See
[decisions.md](decisions.md#decision-4-stateless-authentication).
