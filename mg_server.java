## Daniel Ramirez
## Mafia Game - Server

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Server{
  private static int portNumber = 8011;   
  private static final int HEAD_COUNT = 5;
  
	private ServerSocket serverSocket;
	private AtomicInteger clientId = new AtomicInteger(0);
	 
  private Set<String> allUsers = new HashSet<String>();
  private Set<String> aliveUsers = new HashSet<String>();
  
  private Map<String, DataInputStream> insMap = new ConcurrentHashMap<String, DataInputStream>();
  private Map<String, DataOutputStream> outsMap = new ConcurrentHashMap<String, DataOutputStream>();
  
  private Map<String, String> accuseMap = new HashMap<String, String>();

  private GameState ps = GameState.WAITING; 
  private LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<Event>();
  
  private String mafiaName = null;
  private String copName = null;

	public void start(int port) {
		try {
			serverSocket = new ServerSocket(port);
		} catch (Exception e) {
			System.err.println(e.getMessage());
		}
	}
	
	public Socket accept() throws IOException {
		if (serverSocket == null) {
			throw new RuntimeException("Server socket not initialized!");
		}
		return serverSocket.accept();
	}
	
	private class WorkThread extends Thread {		
		Socket socket;
		public WorkThread(Socket socket) {
			this.socket = socket;
		}
		
		public void run() {
			String userName = null;
			try {
        userName = "client" + clientId.incrementAndGet();
        
				if (!authenticate(userName, socket)) {
          socket.close();
          return;
				}        
        
				int typeCode = -1;
				DataInputStream input = insMap.get(userName);
				
				while (true) {
				  typeCode = input.readInt();
				  MessageType mt = MessageType.get(typeCode);
				  if (mt == null) {
				    System.err.println("Bad message type value: " + typeCode);
				    continue;
				  }
				  
				  switch (mt) {
				  case MAFIA_OFFER_KILL:
				    String whoToKill = input.readUTF();
				    addEvent(new Event(EventType.MAFIA_MADE_KILL, null, whoToKill));
				    break;
				  case COP_OFFER_ACCUSE:
            String whoToAccuse = input.readUTF();
            addEvent(new Event(EventType.COP_MADE_ACCUSE, null, whoToAccuse));
				    break;
				  case PUBLIC_OFFER_ACCUSE:
				    String currUser = input.readUTF();
            String toAccuse = input.readUTF();
            addEvent(new Event(EventType.PUBLIC_MADE_ACCUSE, currUser, toAccuse));
				    break;
				  }
				}
			} catch (Exception e) {
				System.err.println("Exception occurred on user's connection" 
						+ (userName == null ? "" : ": " + userName) 
						+ ", cause: " + e.getLocalizedMessage());
			} finally {
				userGetOffline(userName);
			}
				
		}
	}
	
	private enum EventType {
	  GAME_KICK_OFF,
	  MAFIA_PLZ_MAKE_KILL,
    MAFIA_MADE_KILL,
	  COP_PLZ_MAKE_ACCUSE,	
    COP_MADE_ACCUSE,    
	  PUBLIC_PLZ_MAKE_ACCUSE,
    PUBLIC_MADE_ACCUSE,
    USER_ONLINE,
	  USER_OFFLINE
	}
	
	private class Event {
	  
	  private String user;
	  private EventType evtType;
	  private Object attach;
	  
	  public Event(EventType evtType, String user, Object attach) {
	    this.evtType = evtType;
      this.user = user;
	    this.attach = attach;
	  }
	  
	  public String getUser() {
	    return user;
	  }
	  public EventType getEventType() {
	    return evtType;
	  }
	  public Object getEventAttach() {
	    return attach;
	  }
	}
	
	
	private void addEvent(Event evt) {
	  events.offer(evt);
	}
	
	private Thread holder = new Thread() {
	  public void run() {
	    while (true) {
	      Event evt = null;
        try {
          evt = events.poll(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        if (evt == null) continue;
	      
	      processEvent(evt);
	    }
	  }

	  //when user is null it means it's a global event
	  public void processEvent(Event evt) {
	    switch (ps) {
	    case WAITING:
	      switch (evt.getEventType()) {
	      case USER_ONLINE:
	        for (DataOutputStream tmp : outsMap.values()) {
	          try {          
              tmp.writeInt(MessageType.INFO.val);
              tmp.writeUTF("User '" + evt.getUser() + "' got online, now we have " + allUsers.size() + " player(s): " + allUsers);
	          } catch (IOException e) {
	            e.printStackTrace();
	          }
	        }

	        if (allUsers.size() == HEAD_COUNT) {
	          addEvent(new Event(EventType.GAME_KICK_OFF, null, null));
	        }
	        break;
	      case USER_OFFLINE:
	        userOffline(evt.getUser());
	        break;
	      case GAME_KICK_OFF: 
          allocateRoles();
	        for (DataOutputStream output : outsMap.values()) {
	          try {
	            output.writeInt(MessageType.EVERYONE_CLOSE_EYES.val);
	          } catch (IOException e) {
	            e.printStackTrace();
	          }          
	        }
	        aliveUsers.addAll(allUsers);
          ps = GameState.ROUND_STARTED;
	        addEvent(new Event(EventType.MAFIA_PLZ_MAKE_KILL, null, null)); //MAFIA_PLZ_MAKE_KILL
	        break;	      
	      }
	      break;
	    case ROUND_STARTED:
	      switch (evt.getEventType()) {
        case MAFIA_PLZ_MAKE_KILL:
          DataOutputStream output = outsMap.get(mafiaName);
          try {
            output.writeInt(MessageType.MAFIA_OPEN_EYES.val);
            output.writeInt(MessageType.MAFIA_MAKE_KILL.val);
          } catch (IOException e) {
            e.printStackTrace();
          }
          ps = GameState.WAIT_MAFIA_KILL;
          break;
        case USER_OFFLINE:
          userOffline(evt.getUser());
          resetGame();
          break;
	      }
	      break;
	    case WAIT_MAFIA_KILL:
	      switch (evt.getEventType()) {
	      case MAFIA_MADE_KILL:
	        String killed = (String)evt.getEventAttach();
	        aliveUsers.remove(killed);

          for (DataOutputStream output : outsMap.values()) {
            try {
              output.writeInt(MessageType.MAFIA_CLOSE_EYES.val);
            } catch (IOException e) {
              e.printStackTrace();
            }
          }

          for (DataOutputStream output : outsMap.values()) {
            try {
              output.writeInt(MessageType.EVERYONE_OPEN_EYES.val);
            } catch (IOException e) {
              e.printStackTrace();
            }          
          }
          
	        for (DataOutputStream output : outsMap.values()) {
            try {
              output.writeInt(MessageType.KILLED_BY_MAFIA.val);
              output.writeUTF(killed);
            } catch (IOException e) {
              e.printStackTrace();
            }          
          }
	        
	        if (aliveUsers.size() == 2 || killed.equals(copName)) { //mafia win
	        	System.out.println("Maifa won the game, remaining active users: " + aliveUsers);
	          for (DataOutputStream output : outsMap.values()) {
	            try {
	              output.writeInt(MessageType.MAFIA_WIN.val);
	            } catch (IOException e) {
	              e.printStackTrace();
	            }          
	          }
	          cleanup();
	          System.out.println("Started another round of game...");
	          addEvent(new Event(EventType.GAME_KICK_OFF, null, null));
	        } else {
	          ps = GameState.MAFIA_MADE_KILL;
	          addEvent(new Event(EventType.COP_PLZ_MAKE_ACCUSE, null, null)); //COP_PLZ_MAKE_ACCUSE
	        }
	        
	        break;
        case USER_OFFLINE:
          userOffline(evt.getUser());
          resetGame();
          break;
	      }
	      break;
      case MAFIA_MADE_KILL:
        switch (evt.getEventType()) {
        case COP_PLZ_MAKE_ACCUSE:

          DataOutputStream output = outsMap.get(copName);
          try {
            output.writeInt(MessageType.COP_OPEN_EYES.val);
            output.writeInt(MessageType.COP_MAKE_ACCUSE.val);
          } catch (IOException e) {
            e.printStackTrace();
          }
          ps = GameState.WAIT_COP_ACCUSE;
          break;
        case USER_OFFLINE:
          userOffline(evt.getUser());
          resetGame();
          break;
        }
        break;
      case WAIT_COP_ACCUSE:
        switch (evt.getEventType()) {
        case COP_MADE_ACCUSE:
          String accused = (String)evt.getEventAttach();
          DataOutputStream output = outsMap.get(copName);
          if (accused.equals(mafiaName)) {
            try {
              output.writeInt(MessageType.COP_ACCUSE_RIGHT.val);
            } catch (IOException e) {
              e.printStackTrace();
            }
          } else {
            try {
              output.writeInt(MessageType.COP_ACCUSE_WRONG.val);
            } catch (IOException e) {
              e.printStackTrace();
            }
          }

          for (DataOutputStream tmp : outsMap.values()) {
            try {
              tmp.writeInt(MessageType.COP_CLOSE_EYES.val);
            } catch (IOException e) {
              e.printStackTrace();
            }
          }
          
          ps = GameState.COP_MADE_ACCUSE;
          addEvent(new Event(EventType.PUBLIC_PLZ_MAKE_ACCUSE, null, null)); //PUBLIC_PLZ_MAKE_ACCUSE
          break;
        case USER_OFFLINE:
          userOffline(evt.getUser());
          resetGame();
          break;
        }
        break;
      case COP_MADE_ACCUSE:
        switch (evt.getEventType()) {
        case PUBLIC_PLZ_MAKE_ACCUSE: 

          for (DataOutputStream tmp : outsMap.values()) {
            try {
              tmp.writeInt(MessageType.EVERYONE_OPEN_EYES.val);
            } catch (IOException e) {
              e.printStackTrace();
            }
          }

          for (DataOutputStream output : outsMap.values()) {
            try {
              output.writeInt(MessageType.PUBLIC_MAKE_ACCUSE.val);
            } catch (IOException e) {
              e.printStackTrace();
            }          
          }
          ps = GameState.WAIT_PUBLIC_ACCUSE;
          break;    
        case USER_OFFLINE:
          userOffline(evt.getUser());
          resetGame();
          break; 
        }
        break;
      case WAIT_PUBLIC_ACCUSE:
        switch (evt.getEventType()) {
        case PUBLIC_MADE_ACCUSE:          
          String user = evt.getUser();
          String accused = (String)evt.getEventAttach();
          accuseMap.put(user, accused);
          
          if (accuseMap.size() == aliveUsers.size()) { //all accusation gathered
            Map<String, Integer> votes = new HashMap<String, Integer>();
            
            for (String vote : accuseMap.values()) {
              if (votes.containsKey(vote)) {
                votes.put(vote, votes.get(vote) + 1);
              } else {
                votes.put(vote, 1);
              }
            }
            
            String votedUser = null;
            int max = 0;
            boolean overOneElection = false;
            for (String vote : votes.keySet()) {
              int currVoteCnt = votes.get(vote);
              if (currVoteCnt > max) {
                votedUser = vote;
                max = currVoteCnt;
                overOneElection = false;
              } else if (votes.get(vote) == max) {
                overOneElection = true;
              } 
            }
            
            if (!overOneElection) {
              for (DataOutputStream output : outsMap.values()) {
                try {
                  output.writeInt(MessageType.KILLED_BY_VOTE.val);
                  output.writeUTF(votedUser);
                } catch (IOException e) {
                  e.printStackTrace();
                }          
              }
              
              aliveUsers.remove(votedUser);
              
              if (votedUser.equals(mafiaName)) {
    	        	System.out.println("Maifa was voted to die, the cop won the game, remaining active users: " + aliveUsers);
                for (DataOutputStream output : outsMap.values()) {
                  try {
                    output.writeInt(MessageType.COP_WIN.val);
                  } catch (IOException e) {
                    e.printStackTrace();
                  }          
                }
                cleanup();
                System.out.println("Started another round of game...");
                addEvent(new Event(EventType.GAME_KICK_OFF, null, null));
              } else if (votedUser.equals(copName) || aliveUsers.size() <= 3) {
    	        	System.out.println("Maifa won the game, remaining active users: " + aliveUsers);
                for (DataOutputStream output : outsMap.values()) {
                  try {
                    output.writeInt(MessageType.MAFIA_WIN.val);
                  } catch (IOException e) {
                    e.printStackTrace();
                  }          
                }
                cleanup();
                System.out.println("Started another round of game...");
                addEvent(new Event(EventType.GAME_KICK_OFF, null, null));
              } else {
                ps = GameState.ROUND_STARTED;

                for (DataOutputStream output : outsMap.values()) {
                  try {
                    output.writeInt(MessageType.EVERYONE_CLOSE_EYES.val);
                  } catch (IOException e) {
                    e.printStackTrace();
                  }          
                }
                
                addEvent(new Event(EventType.MAFIA_PLZ_MAKE_KILL, null, null)); //MAFIA_PLZ_MAKE_KILL
              }
            } else {
            	accuseMap.clear();
              for (DataOutputStream output : outsMap.values()) {
                try {
                  output.writeInt(MessageType.INFO.val);
                  output.writeUTF("Accuse invalid.");
                } catch (IOException e) {
                  e.printStackTrace();
                }          
              }
              
              for (DataOutputStream output : outsMap.values()) {
                try {
                  output.writeInt(MessageType.PUBLIC_MAKE_ACCUSE.val);
                } catch (IOException e) {
                  e.printStackTrace();
                }          
              }
            }
            
          }
          break;
        case USER_OFFLINE:
          userOffline(evt.getUser());
          resetGame();
          break;
        }
        break;
	    }
	  }
	  
	  public void userOffline(String user) {
	    for (DataOutputStream output : outsMap.values()) {
        try {
          output.writeInt(MessageType.INFO.val);
          output.writeUTF("User left the game: " + user + ", now we have " + allUsers.size() + " player(s): " + allUsers);
        } catch (IOException e) {
          e.printStackTrace();
        }          
      }
	  }
	  
	  public void resetGame() {
      //reset the game
      for (DataOutputStream output : outsMap.values()) {
        try {
          output.writeInt(MessageType.GAME_RESET.val);
        } catch (IOException e) {
          e.printStackTrace();
        }          
      }
      cleanup();
	  }
	};
	
	private void cleanup() {
    ps = GameState.WAITING; //TODO
	  aliveUsers.clear();
	  accuseMap.clear();
	  mafiaName = null;
	  copName = null;
	}

  public boolean authenticate(String user, Socket socket) {
    
    DataInputStream input = null;
    DataOutputStream output = null;

    try {
      input = new DataInputStream(
          socket.getInputStream());
      output = new DataOutputStream(
          socket.getOutputStream());
    } catch (IOException e1) {
      e1.printStackTrace();
    }
   
    synchronized(allUsers) {
      try {
        if (allUsers.size() == HEAD_COUNT) {
          output.writeInt(MessageType.INFO.val);
          output.writeUTF("No vacancy for new member!");
          return false;
        } else {
          output.writeInt(MessageType.CLIENT_ID_NOTIF.val);
          output.writeUTF(user);

          allUsers.add(user);
          insMap.put(user, input);
          outsMap.put(user, output);     
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }    

    addEvent(new Event(EventType.USER_ONLINE, user, null)); //user online
    return true;
  }
  
  Random r = new Random(System.currentTimeMillis());
  
  private void allocateRoles() {
    List<String> names = new ArrayList<String>(allUsers);
    
    int copIdx = r.nextInt(HEAD_COUNT);
    int mafiaIdx = copIdx;
    while (mafiaIdx == copIdx) {
      mafiaIdx = r.nextInt(HEAD_COUNT);
    }
    mafiaName = names.get(mafiaIdx);
    copName = names.get(copIdx);
    System.out.println("Role allocated - ");
    System.out.println("\tmafia: " + mafiaName);
    System.out.println("\tcop: " + copName);
    names.remove(mafiaName);
    names.remove(copName);
    System.out.println("\tothers: " + names);
    
    for (String name : allUsers) {
      try {
        DataOutputStream output = outsMap.get(name);        
        output.writeInt(MessageType.ROLE_NOTIF.val);
        
        if (name.equals(mafiaName)) {
          output.writeUTF("mafia");
        } else if(name.equals(copName)) {
          output.writeUTF("cop");
        } else {
          output.writeUTF("others");
        }       

        output.writeInt(MessageType.SYNC_PLAYER_LIST.val);
        output.writeUTF(allUsers.toString());
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
  
  public void userGetOffline(String user) {
    synchronized (allUsers) {
      if (!allUsers.contains(user)) {
        return;
      }

      insMap.remove(user);
      outsMap.remove(user);
      
      allUsers.remove(user);      
      addEvent(new Event(EventType.USER_OFFLINE, user, null)); //Get offline
    }
  }
  
  public static void main(String[] args) {
    Server server = new Server();
    server.holder.setDaemon(true);
    server.holder.start();
    try {
      server.start(portNumber);
      System.out.println("Listening for incoming connections...");
      while (true) {
        Socket socket = server.accept();
        System.out.println("Connection established with: " + socket.getInetAddress());
        server.new WorkThread(socket).start();
      }
    } catch (Exception e) {
      System.out.println(e.getMessage());
    }
  }
}
