# Chat_Service-Sockets
### Project Description:

-This project consists of a common chat room.

-There is a graphical user interface to facilitate the use of the application.

-A user can either sign up or log in with their username and password.

-Once a user is logged in, all messages will appear, even those sent while they were disconnected.

-All sent messages will appear directly on the interface.

-When a user logs in or logs out, connected users will be informed.

-All accounts will remain as long as the server is running.

-All data structures used in this project are thread-safe.

-Connections are well managed, so no problems can arise.

### Note:

-Case sensitive

-A username must contain only letters, numbers, and "_", and it must start with a letter.

-A password must be at least 8 characters long and must contain an uppercase letter, a number, and a special character.

#### Example:

##### username: adam

##### password: Adam123*

-Two users cannot log in to the same account at the same time.

-A username is unique.

-"signup" and "login" are keywords: you can switch between "login" and "signup" with the keywords if you wish to change (if you are in "login" and want to switch, simply type "signup" and vice versa. It doesn't matter when you write the keywords as long as you haven't yet done "login" or "signup").

### Deployment

To deploy the project, simply run ChatServer.java, then as many instances of ChatClient.java as you want and chat.
