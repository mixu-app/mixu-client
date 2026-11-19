# Verified email sign-in

Status: **Lab accepted; ordinary-network acceptance pending** · CTX-0058

Local import and reading remain available in guest mode. Network permission
supports optional hosted services; opening the application does not request an
email code or submit a paid AI query.

## Verification boundary

The sign-in form requires an email and a six-digit code. A failed send or verify
request never creates a local authenticated account. Successful verification must
include a nonblank bearer credential, positive bounded expiry and a verified
profile with a user identifier and username. The client consumes the hosted
`profile` response and does not infer identity from an email header.

Protected managed requests attach the current credential. Guest identity headers
cannot authenticate managed requests. Redirect following is disabled so a bearer
credential cannot be forwarded to another origin. Credentials are bound to the
HTTPS endpoint that issued them and are not passed to custom self-hosted
endpoints, including explicitly enabled stateless mode.

## Credential lifecycle

AndroidKeyStore owns an AES key. The client writes only AES-GCM authenticated
ciphertext through an atomic file in the application's backup-excluded storage.
Tokens are never persisted in reader settings, the book database, identity maps
or log strings. Corrupt, expired or endpoint-mismatched stored sessions restore
as guests. Logout removes the encrypted credential. An expired or rejected
managed session requires email verification again; there is no refresh-token
flow in the current protocol.

Disposed UI owners discard unfinished verification, wallet and quota work. Session
storage uses atomic snapshot comparison across Activity owners: stale saves,
rejections and expiry callbacks cannot replace or delete a newer owner's record.
A rejected old request cannot sign out a newer credential. Credential persistence
must succeed before the UI adopts a verified session. The existing guest reading
and explicit self-hosted configuration remain independent of the managed
account.

## Account balance

A verified account refreshes its managed wallet on account view entry and after
sign-in. The refresh is cancellable, scoped to the current owner and credential,
and supports explicit retry. Guest mode sends no wallet request. Only the exact
numeric wallet response for the current user is accepted; unavailable or
malformed responses display an unavailable state rather than a fabricated default
balance. Local reward, check-in, subscription and top-up simulations are not
presented as account actions.

## Account storage and download state

The account view reads the managed storage quota with the same owner, credential
and cancellation boundaries as wallet hydration. Guest mode sends no quota
request. User ownership, numeric types, subscription status and the checked sum
of base storage plus subscription bonus are validated. Used storage may exceed
quota during a server-reported grace or restriction state.

Storage level, limits and progression metrics come only from the response.
Missing modern fields remain unavailable; the client does not infer level one,
zero usage or plan limits from local defaults. Loading, failure and explicit
retry remain visible.

Permanent cloud storage and its five-level progression are displayed separately
from today's download allowance and any server-reported temporary download
boost. Existing download-boost policy is unchanged. No local button grants
storage, credits, download boosts, subscriptions or payment benefits; these
account operations are currently unavailable. Compatibility models and
network helpers remain unchanged for separately verified future flows.

## Verified daily check-in

Daily check-in is an explicit authenticated action. The request uses the existing
managed endpoint with an empty JSON object, leaving the date to the server. The
client accepts only strict boolean result flags and a validated quota snapshot
owned by the current account and confirming today is checked in. Success replaces
the displayed snapshot; it never increments local growth metrics. An already
recorded day remains disabled, and an absent server check-in flag makes the
action unavailable.

Account wallet, quota and check-in calls also carry the credential captured by
the initiating UI intent. Dispatch selects the live credential once and requires
that exact instance to match. A queued old-account action is suppressed after
replacement or logout; an already selected request retains its original bearer
and rejection identity instead of acquiring the new account's token.

Only one check-in may be in flight. Starting it invalidates older quota reads,
and quota refresh remains disabled until it finishes. Disposed owners, cancelled
coroutines and replaced credentials cannot adopt results. Failures retain the
previous snapshot and show a retryable error. Guest mode never posts a check-in.
This does not enable rewarded ads, makeup check-ins or payment operations.

## Verification

`just check` covers malformed verification responses, incomplete code rejection,
managed headers and endpoint separation, expiry/logout/restoration, encrypted
round-trip and tamper detection. The sign-in source accepted in the lab passed
**123 tests**. The subsequent read-only quota change passes **133 tests**, including
strict quota parsing, missing-field behavior, guest request suppression and
request-owner invalidation, explicit check-in acknowledgements and read/write
ordering. `just assemble-debug` also passes. The quota and check-in views
still require joint authenticated device acceptance; its local gate result does
not extend the earlier lab acceptance.
Root-owned Android lab acceptance confirmed real email-code sign-in, the managed
wallet response, AndroidKeyStore-encrypted credential persistence, debug APK
upgrade, process restart, rotation, and logout followed by guest restoration.

The lab used an SSH/ADB network transport while preserving normal hostname and
TLS certificate verification. It used no TLS bypass or additional certificate
authority. This confirms the application flow through that controlled transport;
the ordinary public Android network route remains unaccepted because of the
local network/DNS problem. No claim of ordinary-network end-to-end readiness is
made. Test fixtures use synthetic credentials only; real mailbox and token data
are excluded from repository evidence.
