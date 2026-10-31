# Username/password authentication and JWT

MyBatis accesses accounts in MySQL `member_account`. Register, then sign in with username/password to obtain a JWT. Subsequent requests use `Authorization: Bearer <accessToken>`. HTTP Basic, server-side sessions and authentication cookies are disabled.

## Register

`POST /api/v1/auth/register` is public:

```json
{"username":"alice","password":"My-local-password-123!"}
```

Usernames are trimmed and lowercased, then must match `^[a-z][a-z0-9_]{2,31}$`: 3–32 ASCII letters, digits or underscores, starting with a letter. `Alice_01` becomes `alice_01`. Email addresses, non-ASCII letters, internal spaces and leading digits are rejected. The account has a generated UUID primary key `id` and a unique `username`, with no email column. There is no username-change endpoint.

Passwords require 8–72 characters and at most 72 UTF-8 bytes, including multibyte characters. Password whitespace is preserved. Passwords are BCrypt hashes with cost 12, never plaintext, and are excluded from responses.

Success returns HTTP 201 without automatically signing in:

```json
{"id":"11bd4e77-c44b-4d86-b936-d7a2c36e9f25","username":"alice","createdAt":"2026-10-10T12:00:00Z"}
```

## Sign in

`POST /api/v1/auth/login` uses the same request shape, without an Authorization header. Login checks a non-empty password and the maximum length; registration still requires at least eight characters.

`schema.sql` manages the default account `admin / elec5620`. With `spring.sql.init.mode=always`, each startup inserts it if missing or resets its password and enables it, preserving ID and creation time. Other accounts are unaffected. With mode `never`, SQL must be executed manually. No Java default-user initialiser or default-user YAML setting is used. This account has no extra administrative or expert permissions.

Success returns HTTP 200 and `Cache-Control: no-store`:

```json
{
  "accessToken":"eyJ...",
  "tokenType":"Bearer",
  "expiresIn":900,
  "expiresAt":"2026-10-10T12:15:00Z",
  "user":{"id":"11bd4e77-c44b-4d86-b936-d7a2c36e9f25","username":"alice","createdAt":"2026-10-10T12:00:00Z"}
}
```

`expiresIn` is in seconds. An unknown username, incorrect password or disabled account returns the same `401 INVALID_CREDENTIALS` response with "Incorrect username or password."

## Current member and ownership

`GET /api/v1/auth/me` requires a Bearer token and returns id, username and createdAt. Dietary and health APIs use the same authentication.

JWT `sub` is `member_account.id`, also used as record `memberId`; clients cannot choose another member identity. Accessing another member record returns 404. Disabled or deleted accounts are rejected even with an otherwise valid token because availability is checked per request.

## JWT settings

Keep secrets in `config/api-keys.yml`, excluded from Git and the JAR:

```yaml
nutrimarket:
  security:
    jwt:
      secret: "${JWT_SECRET:}"
      issuer: nutrimarket
      audience: nutrimarket-api
      access-token-ttl: 15m
```

Generate a key with `openssl rand -base64 32` and place it in secret or the JWT_SECRET environment variable. The template has no shared usable key. Missing or short keys prevent startup; at least 32 decoded bytes are required. Token lifetime is between one minute and 24 hours, defaulting to 15 minutes.

Spring Security handles encoding, decoding and Bearer filtering with HS256. Checks include signature, issuer, audience, exp, iat, UUID subject, token_use=access and identity_type=member_id. Expired, unsigned or incorrectly signed tokens are rejected. Tokens contain identity, not passwords or health records.

Restarting with the same key preserves unexpired tokens. Multiple instances must share the key. Rotation invalidates existing tokens. Keep the key on the server; the browser uses only the access token.

## Frontend flow

1. Register at `/register.html`, then sign in at `/login.html`.
2. The frontend stores the token in the current tab sessionStorage for page navigation and refresh, and attaches Bearer headers centrally.
3. `/auth/me` supplies current identity; `/`, `/upload.html` and `/record.html?id=UUID` handle meals.
4. HTTP 401 clears the token and redirects to login. Sign in again instead of retrying indefinitely.
5. Sign-out clears browser storage. There is no server-side single-token revocation; an issued token may remain usable until expiry unless the account is disabled or the signing key is rotated.

Refresh tokens, password reset and login rate limiting are not implemented. Public deployment needs HTTPS and gateway rate limits. Configure specific allowed origins if adding a cross-origin frontend.

## Errors

```json
{"code":"INVALID_CREDENTIALS","message":"Incorrect username or password.","timestamp":"2026-10-10T12:00:00Z"}
```

| HTTP | Code | Meaning |
|---|---|---|
| 400 | INVALID_REQUEST | Invalid username, password length or JSON fields |
| 400 | INVALID_PASSWORD | Password exceeds 72 UTF-8 bytes |
| 409 | USERNAME_ALREADY_REGISTERED | Duplicate username, including concurrent registration |
| 401 | INVALID_CREDENTIALS | Unknown username, wrong password or disabled account |
| 401 | UNAUTHORIZED | Missing, invalid or expired token, or unavailable account |
| 403 | FORBIDDEN | Access denied |

## Migration and tests

New databases use schema.sql. Username-primary-key databases must stop the application and apply [migration 002](../database/migrations/002_member_id_primary_key.sql), which creates UUID ownership and foreign keys. Email-era databases need 001 followed by 002. See [migration instructions](../database/migrations/README.md). Old JWTs are rejected; members sign in again using their username and original password. Repeated default-account initialisation preserves its UUID.

`./mvnw test` covers keys and ownership, username normalisation, old-token rejection, idempotent uploads, registration, login, hashing, expiry, claims, concurrent registration, disabled users and dietary workflows. Tests default to H2. [MySQL test instructions](API.md#mysql-integration-tests) describe isolated destructive test databases; never point tests at business data.

References: [Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [password storage](https://docs.spring.io/spring-security/reference/7.0/features/authentication/password-storage.html).
