import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.Scanner;

public class Client {
    private static ObjectOutputStream out;
    private static ObjectInputStream in;
    private static final Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) {
        System.out.println("Enter server IP:");
        String serverIP = scanner.nextLine();
        System.out.println("Enter server Port:");
        int serverPort = Integer.parseInt(scanner.nextLine());

        try (Socket socket = new Socket(serverIP, serverPort)) {
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            System.out.println("Connected to the server at " + serverIP + ":" + serverPort);

            // Identify and connect
            identifyAndConnect();

            // Display leaderboard and connected players
            displayLeaderboardAndPlayers();

            // Display available games and join or create game
            joinOrCreateGame();

            // Participate in the game rounds
            participateInGame();
            
        } catch (IOException | ClassNotFoundException e) {
            System.out.println("An error occurred: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void identifyAndConnect() throws IOException, ClassNotFoundException {
        System.out.println("Enter your nickname:");
        String nickname = scanner.nextLine();
        out.writeObject(nickname);
        out.flush();

        // Receive a confirmation from the server
        String confirmation = (String) in.readObject();
        System.out.println("Confirmation received: " + confirmation);
    }

    private static void displayLeaderboardAndPlayers() throws IOException, ClassNotFoundException {
        // Receive and display the leaderboard
        List<String> leaderboard = (List<String>) in.readObject();
        System.out.println(" ///Leaderboard//////");
        leaderboard.forEach(System.out::println);

        // Receive and display connected players
        List<String> connectedPlayers = (List<String>) in.readObject();
        System.out.println(" ///ConnectedPlayers//////");
        connectedPlayers.forEach(player -> System.out.println("#" + player));
    }

    private static void joinOrCreateGame() throws IOException, ClassNotFoundException {
        // Receive a list of games from the server
        List<String> gamesList = (List<String>) in.readObject();
        System.out.println("+++++++++++++++++++++++");
        System.out.println("Available games:");
        System.out.println("Game Name \t#Players\tisActive\tisLocked");
        gamesList.forEach(System.out::println);
        System.out.println("+++++++++++++++++++++++");
        System.out.println("Enter the name of the game to create or join:");
        String gameName = scanner.nextLine();
        out.writeObject(gameName);
        out.flush();

        // Receive confirmation of game joined or created
        String response = (String) in.readObject();
        System.out.println(response);
    }

    private static void participateInGame() throws IOException, ClassNotFoundException {
        boolean gameActive = true;
        while (gameActive) {
            Object serverMessage = in.readObject();
            if (serverMessage instanceof String) {
                String message = (String) serverMessage;
                if (message.equals("PING")) {
                    //System.out.println("Received PING from server. Sending PONG...");
                    out.writeObject("PONG");
                    out.flush();
                } else if (message.contains("has started")) {
                    System.out.println(message);
                    int guess = getValidNumber();
                    out.writeObject(guess);
                    out.flush();
                } else if (message.contains("Leader")) {
                    System.out.println(message);
                    String leaderResponse = scanner.nextLine();
                    out.writeObject(leaderResponse);
                    out.flush();
                } else if (message.contains("eliminated") || message.contains("winner") || message.startsWith("Your points")) {
                    System.out.println(message);
                } else if (message.equals("Game over")) {
                    gameActive = false;
                    System.out.println("Game over. Exiting.");
                } else {
                    System.out.println(message);
                }
            }
        }
    }

    private static int getValidNumber() {
        int number;
        do {
           // System.out.println("Choose a number between 0 and 100:");
            while (!scanner.hasNextInt()) {
                System.out.println("That's not a valid number. Please enter a number between 0 and 100:");
                scanner.next(); // consume the invalid input
            }
            number = scanner.nextInt();
            scanner.nextLine(); // consume the newline after the number
        } while (number < 0 || number > 100);
        return number;
    }
}
