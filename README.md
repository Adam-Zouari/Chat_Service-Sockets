# SocketChat

A Java Swing chat app built with TCP sockets. Create an account, join a shared
conversation, and keep your recent messages across server restarts. SocketChat
uses only the Java standard library.

## Features

- Login and signup forms with optional password visibility.
- Real-time messaging with timestamps and a live online-user list.
- Local message search and unread-message counts.
- Persistent accounts and chat history.
- Automatic reconnection and session recovery after connection interruptions.

## Requirements

- **JDK 11 or newer**, with `java` and `javac` available on `PATH`.
- A graphical desktop for the Swing client. The server can run without a display.
- PowerShell or Git Bash on Windows, or Bash on Linux/macOS.

## Run locally

Run these commands from the project directory.

Build with PowerShell:

```powershell
.\scripts\build.ps1
```

Or build with Bash:

```bash
bash scripts/build.sh
```

Start the server in one terminal:

```powershell
java -cp build/classes/main chat.server.ChatServer
```

Keep that terminal open, then start a client in another terminal:

```powershell
java -cp build/classes/main chat.client.ChatClient
```

Run the client command again to open more chat windows. The default connection is
`127.0.0.1:1903`. Stop the server with **Ctrl+C**.

## Use the app

1. Choose **Sign up** to create an account, or **Log in** to use an existing one.
2. Enter your credentials. Signup also asks for password confirmation; **Show
   password** lets you check what you typed.
3. Write a message and press **Enter** or click **Send**.
4. Use **Search messages** to filter the conversation locally, and **Clear** to
   show all retained messages. The sidebar updates as people join and leave.
5. Use **Help** for guidance or **Log out** to return to the authentication form.

If the connection drops, the conversation stays visible while SocketChat
reconnects automatically. A valid session is restored without entering your
password again while the same client window stays open. Opening a new client
window requires login.

## Configuration

Optional arguments let you choose the connection address and storage directory:

```text
chat.server.ChatServer [port] [bind-address] [data-directory]
chat.client.ChatClient [host] [port]
```

For example, use a different local port:

```powershell
java -cp build/classes/main chat.server.ChatServer 1904
java -cp build/classes/main chat.client.ChatClient 127.0.0.1 1904
```

To chat across a trusted LAN, bind the server to its LAN address and connect
clients to that address. Replace `192.168.1.20` with the server computer's address:

```powershell
java -cp build/classes/main chat.server.ChatServer 1903 192.168.1.20
java -cp build/classes/main chat.client.ChatClient 192.168.1.20 1903
```

SocketChat uses unencrypted TCP and is intended for local or trusted-network use.

## Saved data

The server saves accounts, recent messages, and resumable sessions in
`server-data/`. Passwords are stored as salted hashes. Keep this directory and
start the server from the same project directory to restore your data.

You can also choose an explicit storage location:

```powershell
java -cp build/classes/main chat.server.ChatServer 1903 127.0.0.1 C:/chat-data
```

## Project structure

```text
src/main/java/chat/
  client/             Swing interface and connection management
  server/             Socket server and persistent storage
  protocol/           Shared message framing and validation
src/test/java/chat/   Client and server tests
scripts/              PowerShell and Bash build scripts
build/                Generated classes and UI previews
server-data/          Local server data
```

### Main source files

| File | Purpose |
| --- | --- |
| [ChatClient.java](src/main/java/chat/client/ChatClient.java) | Builds the main window, authentication forms, and message composer. |
| [ChatConnection.java](src/main/java/chat/client/ChatConnection.java) | Handles socket communication, authentication requests, and automatic reconnection. |
| [ChatView.java](src/main/java/chat/client/ChatView.java) | Displays messages and online users, with local search and unread counts. |
| [UiTheme.java](src/main/java/chat/client/UiTheme.java) | Defines shared colors, fonts, and component styling. |
| [ChatProtocol.java](src/main/java/chat/protocol/ChatProtocol.java) | Defines message framing and validation shared by the client and server. |
| [ChatServer.java](src/main/java/chat/server/ChatServer.java) | Manages connections, authentication, sessions, and message broadcasts. |
| [ChatStore.java](src/main/java/chat/server/ChatStore.java) | Saves and loads accounts, chat history, and resumable sessions. |

## Tests

Build and run the test suite with PowerShell:

```powershell
.\scripts\build.ps1 -Test
```

Or with Bash:

```bash
bash scripts/build.sh --test
```

## License

Copyright (c) 2026 Adam Zouari.

SocketChat's source code and documentation are available under the
[PolyForm Strict License 1.0.0](LICENSE). It permits noncommercial use under its
terms and does not grant permission to modify or redistribute the project.
Other uses require separate permission from the copyright holder.
