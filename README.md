# Chat_Service-Sockets

A shared chat room built with Java TCP sockets and a Swing desktop client. Sign up
or log in, chat with everyone connected, and receive the latest 500 messages when
you join. Accounts and recent messages are saved on disk and survive server restarts.

Requires **JDK 11 or newer**. No dependencies or build tool are needed.

## Run

From the project directory, compile the source files:

```powershell
javac --release 11 -encoding UTF-8 -d build *.java
```

Start the server in one terminal:

```powershell
java -cp build ChatServer
```

Start a client in another terminal; run it again for more clients:

```powershell
java -cp build ChatClient
```

The defaults are `127.0.0.1:1903`. Optional arguments:

```powershell
java -cp build ChatServer 1904
java -cp build ChatClient 127.0.0.1 1904
```

Server arguments are `[port] [bind-address] [data-directory]`. The default data
directory is `server-data` in the current working directory. For example:

```powershell
java -cp build ChatServer 1903 127.0.0.1 C:/chat-data
```

Restart from the same directory or pass the same data directory to restore your
accounts and history. Keep this folder when updating or recompiling the app.

To connect across a trusted LAN, bind the server explicitly and use its LAN IP on
the other machine:

```powershell
java -cp build ChatServer 1903 0.0.0.0
java -cp build ChatClient 192.168.1.10 1903
```

Replace the example address with the server's address. The server binds only to
loopback by default. **TCP traffic, including passwords, is unencrypted**; use a
trusted network or an encrypted tunnel for remote access. Do not expose this server
directly to the internet.

## Use

1. Choose **Log in** or **Sign up** on the authentication form.
2. Fill in your username and password. Signup also asks you to confirm the password.
3. Click **Log in** or **Create account**. Passwords are hidden by default; check
   **Show password** to reveal them temporarily.
4. Once connected, type a message and press Enter or click **Send**.

Usernames are case-sensitive, 1-32 ASCII letters/numbers/underscores, and must start
with a letter. `login` and `signup` are reserved, regardless of case. Passwords
must be 8-128 characters with an uppercase letter, a digit, and a special character
other than whitespace. Control characters are rejected. Example: username `adam`,
password `Adam123*` (choose your own password).

Use the form's mode buttons to switch between login and signup. An unfinished
signup does not reserve its username. Only one session can use an account at a time.
Passwords are cleared after submission, when changing forms, and on disconnect;
the desktop client does not save them.

Chat commands are case-sensitive:

| Command | Action |
| --- | --- |
| `/users` | List connected usernames. |
| `/help` | Open a help dialog explaining commands and reconnection. |
| `/quit` | Log out and return to the login/signup forms. |

The chat toolbar also has **Help**, **Online users**, and **Log out** buttons.
After logout you can immediately log in again or create another account.

If the server stops or the connection fails, the client automatically retries every
three seconds and restores your signed-in session when the server returns. You do
not need to enter your password again while that client window remains open and
its session is valid. The chat stays visible while reconnecting, with sending
disabled and **Log out** still available. **Refresh connection** also restores
the current session.

Session restoration uses a random token kept only in the running client's memory;
your password is never cached or replayed. Tokens expire seven days after login.
Expired or invalid sessions return to usable login/signup forms. **Log out** and
`/quit` forget the token immediately and revoke it on the server when connected.
Closing the client also forgets its token, so opening a new window requires login.
An offline logout still signs out locally even if the server cannot acknowledge it.
Before your first login, you can fill out the forms while waiting for a connection.

Join/leave notices and commands are not stored in chat history. Messages can contain
Unicode and are limited to 2,000 Java characters (UTF-16 code units); blank messages
and control characters are rejected. The client displays up to 500 lines or
160,000 characters. Closing its window disconnects it and stops reconnection.
Stop the server with Ctrl+C.

## Saved data

The server stores salted password hashes in `accounts.bin` and the latest 500 chat
messages in `messages.bin`, under `server-data` by default. Hashed resume tokens and
their expiry times are saved separately in `sessions.bin`; raw tokens and passwords
are never written there. It saves changes before confirming them, uses atomic
snapshot replacement, and periodically compacts message history. Accounts, recent
history, and resumable sessions survive normal restarts and abrupt process
termination. Existing data directories without `sessions.bin` remain compatible.
Back up the data directory while the server is stopped.

Only one server may use a data directory at a time. Corrupted files cause a clear
startup error instead of silently resetting your accounts. If storage fails while
running, creation/message delivery cannot be confirmed; repair the storage problem
and restart the server. Existing accounts and history are not automatically erased.

## Limits and implementation

- At most 64 simultaneous connections and 10,000 saved accounts.
- Authentication must finish within 60 seconds. Five wrong login passwords or
  20 invalid authentication inputs close that connection; reconnect to try again.
- Accounts store salted PBKDF2-HMAC-SHA256 password hashes, not plaintext passwords.
- Session tokens are 256 random bits, with SHA-256 hashes on the server, a seven-day
  lifetime, and one token per account. A fresh password login replaces any previous
  token. Resuming an interrupted connection preserves the token and expiry.
- A bounded queue per connection keeps a slow reader from blocking the room;
  clients that cannot keep up are disconnected.
- History replay and live broadcasts share one ordered handoff, avoiding duplicates
  and missing messages while joining.
- The protocol is UTF-8 text with LF or CRLF line endings and a 4,096-character
  physical line limit. Atomic form submissions have explicit success/error responses.
  The client and server share framing/limits in `ChatProtocol.java`; the server
  still accepts the original interactive text protocol for simple socket clients.

## Check

Compile with warnings enabled and run the dependency-free regression harness:

```powershell
javac --release 11 -encoding UTF-8 -Xlint:all -d build *.java tests/*.java
java -cp build ChatServerTest
java -cp build ChatStoreTest
java -cp build ChatConnectionTest
java -cp build ChatClientFormTest
java -cp build ChatSessionTest
```

Tests use ephemeral loopback ports, so a running server on port 1903 is unaffected.
They cover validation, authentication disconnects, simultaneous signup/login,
session ownership, reconnects, UTF-8/framing, history ordering and limits, connection
capacity, stalled-reader isolation, forms, commands, shutdown, persistence after
restart/abrupt exit, write failures, automatic authenticated recovery, token expiry
and revocation, logout/login, password visibility, and form interactions. Networking
and form tests run headlessly; recovery tests forcibly terminate a real server JVM.

For a desktop smoke check, open two clients, sign up, exchange messages, and try
Help and `/quit`. Log in again without reopening the window. Stop and restart the
server and wait for the client to restore the session and saved history. Check
that **Show password** reveals and hides both signup password fields.

See [AUDIT.md](AUDIT.md) for the original findings and fixes.
