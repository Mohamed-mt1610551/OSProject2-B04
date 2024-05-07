package PlayersChat;


import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.*;

public class ChatServer {
    private static final int PORT = 12345;
    private static Map<String, PrintWriter> clients = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        System.out.println("The chat server is running...");
        ServerSocket listener = new ServerSocket(PORT);

        try {
            while (true) {
                new Handler(listener.accept()).start();
            }
        } finally {
            listener.close();
        }
    }

    private static class Handler extends Thread {
        private String name;
        private Socket socket;
        private BufferedReader in;
        private PrintWriter out;

        public Handler(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                // Handle new name registration
                while (true) {
                    out.println("SUBMITNAME");
                    name = in.readLine();
                    if (name == null) {
                        return;
                    }
                    synchronized (clients) {
                        if (!name.isBlank() && !clients.containsKey(name)) {
                            clients.put(name, out);
                            break;
                        }
                    }
                }

                out.println("NAMEACCEPTED");
                clients.values().forEach(writer -> writer.println("MESSAGE " + name + " has joined"));

                // Accept messages from this client and broadcast them.
                while (true) {
                    String input = in.readLine();
                    if (input == null) {
                        return;
                    }
                    for (PrintWriter writer : clients.values()) {
                        writer.println("MESSAGE " + name + ": " + input);
                    }
                }
            } catch (IOException e) {
                System.out.println(e.getMessage());
            } finally {
                // Clean up when a client leaves
                if (name != null && out != null) {
                    clients.remove(name);
                    clients.values().forEach(writer -> writer.println("MESSAGE " + name + " has left"));
                }
                try {
                    socket.close();
                } catch (IOException e) {
                    System.out.println(e.getMessage());
                }
            }
        }
    }
}