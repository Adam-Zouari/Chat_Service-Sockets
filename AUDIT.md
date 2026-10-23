# Code audit

The project remains a dependency-free Java socket server and Swing client. Accounts
and the latest 500 messages are persisted in local files; there is no database,
web service, or build framework.

## Findings and fixes

| Original issue | Change |
| --- | --- |
| Signup reserved an empty-password account before validation; disconnects and mode changes could leak or delete accounts. | Create an account only after a valid password, atomically with its session. |
| Separate map checks allowed simultaneous signup/password overwrites and duplicate login sessions. | Serialize account/session claims and remove a session only when the handler owns it. |
| EOF during authentication dereferenced null or removed an unrelated session. | Explicit EOF handling and connection-local cleanup at every stage. |
| Passwords were retained as plaintext. | Store a unique random salt and PBKDF2-HMAC-SHA256 hash; clear temporary key material where practical. |
| Authentication retries skipped username validation. | Validate every username and password retry; reserve authentication control words. |
| Unlimited clients, input lines, accounts, and message history could exhaust resources. | Bound all of them, impose an absolute authentication deadline, and limit failed attempts. |
| A slow reader could block every broadcast; PrintWriter hid write failures. | Give each connection a bounded output queue and sender; close slow or failed connections. |
| History replay raced live delivery, causing duplicates or incorrect ordering. | Queue history and live events under the same room lock, with socket writes outside it. |
| Platform-default encoding corrupted messages between machines. | Use UTF-8 explicitly and reject malformed input and control-character injection. |
| Swing components were created/modified outside the event thread. | Keep UI operations on the event thread and network operations in background threads. |
| Input before connection could fail; failed connections left the UI apparently usable. | Track connection and authentication state, disable unavailable input, show errors, and close sockets on exit. |
| Password entry was visible. | Mask password prompts and clear the input after sending. |
| An unlimited transcript and queued UI updates could exhaust the client. | Bound both queues and the visible transcript; batch UI updates and bound pasted input. |
| Authentication required typing commands and credentials into the chat composer. | Add regular login/signup forms with labelled fields, signup confirmation, buttons, and explicit authentication success/error frames. |
| `/quit` permanently disabled a client, and server restarts left it stuck. | Separate window lifetime from socket sessions, reconnect automatically, provide Refresh, and return to the forms after logout or connection loss. |
| `/help` was easy to miss in the transcript. | Show a descriptive local help dialog from `/help` or the Help button; retain detailed server help for text clients. |
| Users could not reveal a password to check typing. | Add a Show password checkbox for both signup password fields, with visibility reset on form/session changes. |
| Restarting the server erased every account and message. | Save hashed accounts and bounded history before acknowledging them; load on startup, lock the data directory, and reject corruption without resetting data. |
| Network reconnection required entering a password after every server outage. | Issue a random expiring session token, retain it only in the open client, and persist its hash on the server so authenticated sessions resume automatically after crashes. Explicit logout revokes the token; fresh password login replaces it. |

Small additions: `/users`, `/help`, `/quit`, configurable connection addresses, a
connection status indicator, and a Send button.

## Verification

The regression harness uses real loopback sockets and a fresh server per test. It
contains 19 groups covering authentication EOF and mode switching, validation boundaries, duplicate
signup/login races, session ownership, reconnects after TCP resets, UTF-8, malformed
input, history/live ordering, the 500-message history bound, connection capacity,
stalled-reader isolation, form authentication, commands, persistent server restarts,
storage failures, and shutdown.
Direct protocol checks cover framing, line limits, Unicode, and strict decoding.

Separate storage tests cover reopen, abrupt child-JVM termination, interrupted
records, checksums, bounded compaction, immutable snapshots, file locks, and failed
writes. Desktop-controller tests cover reconnect after outages, logout/login,
refresh, credential clearing, old-session event suppression, and ordered history.
Headless Swing form checks cover modes, validation, password visibility, and buttons.
Session tests cover real process crashes, authenticated automatic recovery,
hash-only persistence, expiry, malformed/cross-account tokens, durable logout,
password-login token replacement, ownership-safe takeover, and legacy compatibility.

A separate real-time check confirmed that a peer trickling an incomplete line is
disconnected at the 60-second authentication deadline, while an authenticated idle
client remains connected and can send afterward.

Compile with `--release 11 -Xlint:all` and run the harness using the commands in
README.md. Desktop-client checks are described there as well.

## Remaining scope

The transport is unencrypted TCP. The default bind address is loopback; remote
access requires an explicit bind address and should use a trusted network or an
encrypted tunnel. Password hashing protects the server's account representation,
not the network traffic. This is still a personal, single-room application with
local persistent accounts and bounded history, not an internet-facing chat service.
