import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ChatServer {
    private static final int PORT = 1903;
    
    //A map that is threadsafe to store users' usernames and their own PrintWriters to facilitate sending messages  
    private static ConcurrentHashMap<String, PrintWriter> clients = new ConcurrentHashMap<>();
    
    //A map that is threadsafe to store users' usernames and passwords
    private static ConcurrentHashMap<String, String> passwordHashMap = new ConcurrentHashMap<>();

    //A list that is threadsafe to store all messages so that new users will not miss the previous messages
    private static ConcurrentLinkedQueue<String> messageHistory = new ConcurrentLinkedQueue<>();

    public static void main(String[] args) {
        try {
            ServerSocket serverSocket = new ServerSocket(PORT);
            System.out.println("Server started on port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                Thread clientThread = new Thread(new ClientHandler(clientSocket));
                clientThread.start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class ClientHandler implements Runnable {
        private Socket clientSocket;
        private PrintWriter clientWriter;
        private BufferedReader reader;
        private String username;
        private String password;
        private boolean test=true;

        public ClientHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
            try {
                reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                clientWriter = new PrintWriter(clientSocket.getOutputStream(), true);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            try {
                // Prompt user for login or signup
                clientWriter.println("Welcome to the chat server!");
                clientWriter.println("Do you want to login or sign up? (login/signup)");
                String choice = reader.readLine().trim().toLowerCase();
                while(!choice.equals("login") && !choice.equals("signup")){
                    clientWriter.println("Invalid choice. Try again");
                    choice = reader.readLine().trim().toLowerCase();
                }
                if (choice.equals("login")){
                    try {
                    while(!login()){
                        if(signup()){
                            break;
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    test=false;
                }
                }
                else {
                    try {
                        while(!signup()){
                            if(login()){
                                break;
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        test=false;

                    }
                }
                    if(test){
                    clients.put(username, clientWriter);
                    clientWriter.println("Welcome, " + username + "! You are now connected.");
                    broadcast2(username+" has joined the chat !");
                    
                    // Send message history to the new client
                    sendHistoryToClient(clientWriter);
            

                String message;
                while ((message = reader.readLine()) != null) {
                    String formattedMessage = username + ": " + message;
                    messageHistory.add(formattedMessage);
                    broadcast(formattedMessage);
                }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                // Cleanup resources
                try {
                    reader.close();
                    clientSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if(test){
                //Informing the others that the user has left the chat
                broadcast2(username+" has left the chat");
                // Remove client from the list
                clients.remove(username);
                }
            }
        }


        //Broadcasting the message to all connected users
        private void broadcast(String message) {
            for (PrintWriter client : clients.values()) {
                client.println(message);
            }
        }


            //Broadcasting the message to all connected users except this user
        private void broadcast2(String message) {
            for (PrintWriter client : clients.values()) {
                if(!client.equals(clientWriter)){
                client.println(message);
                }
            }
        }



        private boolean username_test(String username){
            // Check if username is not empty or null
        if (username == null || username.isEmpty()) {
            return false;
        }



        // Check if the first character is a letter
        char firstChar = username.charAt(0);
        if (!Character.isLetter(firstChar)) {
            return false;
        }



        // Check if username contains only letters, numbers, and underscores
        for (int i = 0; i < username.length(); i++) {
            char ch = username.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '_') {
                return false;
            }
        }
        // Username meets all criteria
        return true;
        }


        //Send the previous messages to the user
        private void sendHistoryToClient(PrintWriter writer) {
            // Send message history to the new client
            for (String message : messageHistory) {
                writer.println(message);
            }
        }



        private boolean password_test(String password) {
            // Check if password has minimum length of 8
            if (password.length() < 8) {
                return false;
            }
            // Check if password contains at least one uppercase character, one number, and one special character
            boolean hasUppercase = false;
            boolean hasNumber = false;
            boolean hasSpecialChar = false;
            for (char ch : password.toCharArray()) {
                if (Character.isUpperCase(ch)) {
                    hasUppercase = true;
                } else if (Character.isDigit(ch)) {
                    hasNumber = true;
                } else if (!Character.isLetterOrDigit(ch)) {
                    hasSpecialChar = true;
                }
                if(hasUppercase && hasNumber && hasSpecialChar)
                return true;
            }
        
            return false;
        }
        


        //Login steps
        private boolean login() throws IOException {
            // Username test
            clientWriter.println("login");
            clientWriter.println("Enter your username:");
            username = reader.readLine();
            if(username.equals("signup")){
                return false;
            }
            while(!username_test(username)){
                clientWriter.println("Invalid username. Try again :");
                username = reader.readLine();
                if(username.equals("signup")){
                    return false;
                }
            }
            while(true){
                if(!passwordHashMap.containsKey(username)){
                clientWriter.println("Username doesn't exist. Try again :");
                username = reader.readLine();
                if(username.equals("signup")){
                    return false;
                }
                }else{
                    if(clients.containsKey(username)){
                        clientWriter.println("User already connected. Try again :");
                        username = reader.readLine();
                        if(username.equals("signup")){
                            return false;
                        }
                    }
                    else{
                        break;
                    }
                }
            }


            clientWriter.println("Enter your password:");
            password = reader.readLine();
            if(password.equals("signup")){
                return false;
            }
            String storedPassword = passwordHashMap.get(username);
                while(!password.equals(storedPassword)) {
                 clientWriter.println("Invalid password. Please try again.");
                 password = reader.readLine();
                 if(password.equals("signup")){
                    return false;
                }
                }
                return true;

        }



        //Signup steps
 
        private boolean signup() throws IOException {
            try {
                clientWriter.println("signup");
                // Username test
                clientWriter.println("Enter your username:");
                username = reader.readLine();
                if(username.equals("login")){
                    return false;
                }
                while (!username_test(username)) {
                    clientWriter.println("Invalid username. Try again :");
                    username = reader.readLine();
                    if(username.equals("login")){
                        return false;
                    }
                }
                while (passwordHashMap.containsKey(username)) {
                    clientWriter.println("Username already exists. Try again :");
                    username = reader.readLine();
                    if(username.equals("login")){
                        return false;
                    }
                }
                passwordHashMap.put(username, "");
        
                // Enter password
                clientWriter.println("Enter your password:");
                password = reader.readLine();
                if(password.equals("login")){
                    return false;
                }
                while (!password_test(password)) {
                    clientWriter.println("Invalid password. Please try again.");
                    password = reader.readLine();
                    if(password.equals("login")){
                        return false;
                    }
                }
                passwordHashMap.put(username, password);
            } finally {
                // If the user disconnected before entering the password, remove the username from passwordHashMap
                if (password == null || password.isEmpty()) {
                    passwordHashMap.remove(username);
                }
            }
            return true;
        }
        

    }
}
