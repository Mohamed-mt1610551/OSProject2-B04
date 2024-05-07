import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.stream.Collectors;

public class Server {
	private static final int PORT = 13337;
	private static List<Game> games = Collections.synchronizedList(new ArrayList<>());
	private static List<Player> allPlayers = Collections.synchronizedList(new ArrayList<>());
	private static List<Ticket> tickets = Collections.synchronizedList(new ArrayList<>());
	private static List<String> leaderboard = Collections.synchronizedList(new ArrayList<>());

	public static void main(String[] args) {
		try {
			initializeDefaultGames();
			System.out.println("Server started on port " + PORT);
			startPinging(); // Start the pinging process

			try (ServerSocket serverSocket = new ServerSocket(PORT)) {
				while (true) {
					Socket clientSocket = serverSocket.accept();
					System.out.println("Client connected: " + clientSocket.getInetAddress().getHostAddress());
					new ClientHandler(clientSocket).start();
				}
			} catch (IOException e) {
				System.out.println("Server error: " + e.getMessage());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void initializeDefaultGames() {
		games.add(new Game("game1"));
		games.add(new Game("game2"));
		games.add(new Game("game3"));
		System.out.println("Default games initialized.");
	}

	private static void startPinging() {
		Thread pingThread = new Thread(() -> {
			while (true) {
				try {
					pingPlayers();
					Thread.sleep(30000); // Ping every 30 seconds and wait for 5seconds to recieve a response
				} catch (InterruptedException e) {
					System.out.println("Pinging thread interrupted: " + e.getMessage());
					break;
				} catch (IOException e) {
					System.out.println("IOException during pinging: " + e.getMessage());
				}
			}
		});
		pingThread.start();
	}

	private static void pingPlayers() throws IOException {
		// Iterate through all players
		for (Player p : new ArrayList<>(allPlayers)) {
			try {
				// Check if player is still connected
				if (!p.getSocket().isClosed()) {
					p.getSocket().setSoTimeout(20000); // Set timeout for ping response

					// Send a ping message
					p.getOut().writeObject("PING");
					p.getOut().flush();

					// Await a pong response for 5 seconds
					String response = (String) p.getIn().readObject();
					if (!"PONG".equals(response)) {
						throw new IOException("Unexpected response from player " + p.getName());
					} else {
						System.out.println("Ping Successful " + p.getName() + " Response: " + response);
					}
				}
			} catch (SocketTimeoutException | ClassNotFoundException e) {
				System.out
						.println("Player " + p.getName() + " timed out or failed to respond correctly, disconnecting.");
				disconnectPlayer(p);
			} catch (IOException e) {
				System.out.println("IO Error with player " + p.getName() + ": " + e.getMessage());
				disconnectPlayer(p);
			}
		}
	}

	private static void disconnectPlayer(Player player) {
		try {
			player.getSocket().close();
		} catch (IOException e) {
			System.out.println("Error while disconnecting player " + player.getName() + ": " + e.getMessage());
		}
		player.setConnected(false);
		allPlayers.remove(player);
		System.out.println("Player " + player.getName() + " has been disconnected.");
	}

	static class ClientHandler extends Thread {
		private Socket socket;
		private ObjectOutputStream out;
		private ObjectInputStream in;
		private Player player;

		public ClientHandler(Socket socket) {
			this.socket = socket;
			try {
				out = new ObjectOutputStream(socket.getOutputStream());
				in = new ObjectInputStream(socket.getInputStream());
			} catch (IOException e) {
				System.out.println("Error setting up streams: " + e.getMessage());
			}
		}

		public void run() {
			try {
				String nickname = (String) in.readObject();
				player = findOrCreatePlayer(nickname);
				out.writeObject("Identification successful. Welcome, " + nickname + "\nYour ticket ID is "
						+ player.getTicket().getTID());
				out.flush();

				updateLeaderboard();
				out.writeObject(leaderboard);
				out.flush();

				out.writeObject(getConnectedPlayers());
				out.flush();

				sendAvailableGames();

				String gameName = (String) in.readObject();
				Game game = null;
				synchronized (games) {
					game = games.stream().filter(g -> g.getGameName().equals(gameName)).findFirst().orElse(null);
					if (game == null) {
						game = new Game(gameName);
						games.add(game);
					}

				}
				game.addPlayer(player);

				while (!socket.isClosed() && player.getPoints() >= 0) {
					// Maintain connection and game logic processing
				}
			} catch (IOException | ClassNotFoundException e) {
				System.out.println("Error handling client: " + e.getMessage());
			} finally {
				try {
					socket.close();
				} catch (IOException e) {
					System.out.println("Error closing socket: " + e.getMessage());
				}
			}
		}

		private void sendAvailableGames() throws IOException {
			List<String> gameNames = games.stream().map(game -> game.getGameName() + "\t\t" + game.getPlayers().size()
					+ "/6\t\t" + game.isActive + "\t\t" + game.isLocked).collect(Collectors.toList());
			out.writeObject(gameNames);
			out.flush();
		}

		private void updateLeaderboard() {
			leaderboard = allPlayers.stream().sorted(Comparator.comparingInt(Player::getTotalWins).reversed()).limit(5)
					.map(p -> p.getTicket().getPseudoName() + " - Wins: " + p.getTotalWins())
					.collect(Collectors.toList());
		}

		private List<String> getConnectedPlayers() {
			return allPlayers.stream().filter(Player::isConnected).map(Player::getName).collect(Collectors.toList());
		}

		private Player findOrCreatePlayer(String pseudoName) {
			for (Player p : allPlayers) {
				if (p.getName().equals(pseudoName) && p.isConnected()) {
					return p;
				}
			}

			Ticket newTicket = new Ticket(pseudoName);
			tickets.add(newTicket);
			Player newPlayer = new Player(newTicket, pseudoName, out, in, socket);
			allPlayers.add(newPlayer);
			return newPlayer;
		}
	}

	static class Game {
		private String gameName;
		private List<Player> players = Collections.synchronizedList(new ArrayList<>());
		private List<Player> playersBroadCast = Collections.synchronizedList(new ArrayList<>());
		private boolean isActive = false;
		private boolean isLocked = false;
		private int roundNumber = 0; // Declare and initialize the round number
		ArrayList<String> eliminatedPlayers = new ArrayList<>();

		public Game(String gameName) {
			this.gameName = gameName;
		}

		public synchronized void addPlayer(Player player) throws IOException, ClassNotFoundException {

			if (playersBroadCast.size() >= 6) {
				isLocked = true;
			}

			if (isLocked == true) {
				// Properly notify the player without adding them to the game
				player.getOut().writeObject(
						"Game is already started or locked. Please wait for the next round or choose another game.");
				player.getOut().flush();
				return; // Return here to prevent adding to the game
			}

			players.add(player);
			playersBroadCast.add(player);
			if (players.size() >= 2) {
				promptLeaderToStartGame();
			} else {
				notifyPlayerGamePending(player);
			}
			// Confirm the player has joined if and only if they are actually added to the
			// game
			player.getOut().writeObject("Joined game: " + this.gameName);
			player.getOut().flush();
		}

		private void promptLeaderToStartGame() throws IOException, ClassNotFoundException {
			Player leader = players.get(0); // Assuming the first player is the leader
			leader.getOut().writeObject("#Players in Lobby: " + playersBroadCast.size()
					+ "\nLeader, do you want to start the game? (yes or no)");
			leader.getOut().flush();
			String leaderResponse = (String) leader.getIn().readObject();
			if ("yes".equalsIgnoreCase(leaderResponse.trim())) {
				isActive = true;
				isLocked = true;
				new Thread(this::runGame).start();
			}
		}

		private void notifyPlayerGamePending(Player player) throws IOException {
			player.getOut().writeObject("Waiting for game to start...");
			player.getOut().flush();
		}

		private void runGame() {
			try {
				while (players.size() > 1 && isActive == true) {

					playRound();
					Thread.sleep(2000); // Delay between rounds
				}
				isActive = false;
				isLocked = false;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				System.out.println("Game run interrupted: " + e.getMessage());
			} catch (Exception e) {
				System.out.println("An error occurred in the game loop: " + e.getMessage());
			}
		}

		private synchronized void playRound() throws IOException, ClassNotFoundException {
			roundNumber++;
			Map<Player, Integer> selections = new HashMap<>();
			double sum = 0;

			if (players.size() == 2) {
				for (Player player : playersBroadCast) {
					if (player.getPoints() > 0) {
						player.getOut().writeObject(
								"Round " + roundNumber + " has started. Please choose a number between (0-100).");
						player.getOut().flush();
						int choice = (int) player.getIn().readObject();
						if (choice == 0) {
							// if player1 chose 0 and the other player did not choose 0
							if (players.indexOf(player) == 0 && players.get(1).getChoice() != 0) {
								choice = players.get(1).getChoice() + 2;
							} else if (players.indexOf(player) == 1 && players.get(0).getChoice() != 0) {
								// if player2 chose 0 and the other player did not choose 0
								choice = players.get(0).getChoice() + 2;
							} else { // both picked 0 do nothing here and decrease both points down
							}
						}

						player.setChoice(choice);
						selections.put(player, choice);
						sum += choice;
					} else {

						player.getOut().writeObject("You are eliminated. Round " + roundNumber + " has begun.");
						player.getOut().flush();
						players.remove(player);
					}
				}

			} else {// if players in the game more than 2
				for (Player player : playersBroadCast) {
					if (player.getPoints() > 0) {
						player.getOut().writeObject(
								"Round " + roundNumber + " has started. Please choose a number between (0-100).");
						player.getOut().flush();
						int choice = (int) player.getIn().readObject();
						player.setChoice(choice);
						selections.put(player, choice);
						sum += choice;
					} else {
						player.getOut().writeObject("You are eliminated. Round " + roundNumber + " has begun.");
						player.getOut().flush();
						players.remove(player);
					}
				}
			}

			// Calculating results and roundWinner
			double result = (sum / selections.size()) * (2 / 3);
			Player winner = null;
			Player p1 = new Player("No Winner", 0);
			double minDiff = Double.MAX_VALUE;

			for (Map.Entry<Player, Integer> entry : selections.entrySet()) {
				double diff = Math.abs(entry.getValue() - result);
				if (diff < minDiff) {
					minDiff = diff;
					winner = entry.getKey();
				} else if (result == 0) {
					winner = p1;
				}
			}

			// decrementing the points for round losers, excepting spectators
			for (Player player : players) {
				if (player != winner) {
					player.decreasePoints();
					if (player.getPoints() <= 0) {
						eliminatedPlayers.add(player.getName());
					}
				}

			}

			// broadCasting results to all lobby players
			for (Player player : playersBroadCast) {

				player.getOut()
						.writeObject("++++++++++++++++++\nRound " + roundNumber + "\nPlayers\t\tPoints\t\tChoice\n"
								+ players.toString() + "\n" + "winner is " + winner.getName() + "\nEliminated Players\n"
								+ eliminatedPlayers + "\n++++++++++++++++++");
				player.getOut().flush();

			}

		}

		public String getGameName() {
			return gameName;
		}

		public List<Player> getPlayers() {
			return players;
		}
	}

	static class Player {
		private String name;
		private int points = 5; // Starting points for each game
		private ObjectOutputStream out;
		private ObjectInputStream in;
		private Socket socket;
		private int choice; // Last number chosen by the player in a game round

		private int totalWins = 0; // Total wins accumulated by the player
		private Ticket ticket; // Ticket associated with the player for identification
		private boolean isConnected = false; // Tracks if the player is currently connected

		public Player(Ticket ticket, String name, ObjectOutputStream out, ObjectInputStream in, Socket socket) {
			this.ticket = ticket;
			this.name = name;
			this.out = out;
			this.in = in;
			this.socket = socket;
			this.isConnected = true; // Set true as default when a player is created
		}

		// Constructor used for initializing default players (for testing or default
		// setup)
		public Player(String name, int totalWins) {
			this.name = name;
			this.points = 5;
			this.isConnected = true;
			this.totalWins = totalWins;
		}

		public void decreasePoints() {
			this.points--;
			if (this.points <= 0) {
				System.out.println(this.name + " has been eliminated from the game.");
			}
		}

		public void increaseWins() {
			this.totalWins++;
		}

		// Getters and setters
		public String getName() {
			return this.name;
		}

		public int getPoints() {
			return this.points;
		}

		public ObjectOutputStream getOut() {
			return this.out;
		}

		public ObjectInputStream getIn() {
			return this.in;
		}

		public Socket getSocket() {
			return this.socket;
		}

		public int getTotalWins() {
			return this.totalWins;
		}

		public boolean isConnected() {
			return this.isConnected;
		}

		public void setConnected(boolean isConnected) {
			this.isConnected = isConnected;
		}

		public Ticket getTicket() {
			return this.ticket;
		}

		@Override
		public String toString() {
			return this.name + "\t\t" + this.points + "\t\t" + this.choice + "\n";
		}

		public int getChoice() {
			return choice;
		}

		public void setChoice(int choice) {
			this.choice = choice;
		}
	}

	static class Ticket {
		private final UUID TID;
		private final String pseudoName;

		public Ticket(String pseudoName) {
			this.TID = UUID.randomUUID();
			this.pseudoName = pseudoName;
		}

		public UUID getTID() {
			return TID;
		}

		public String getPseudoName() {
			return pseudoName;
		}
	}
}
