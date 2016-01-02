## Daniel Ramirez 
## Mafia Game - Client

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;


public class Client {
	private InetAddress address;
	private final int port = 8011;
	private Socket client_socket;
	private DataOutputStream output;
	private DataInputStream input;
	private String clientName = "unassigned";
	
	private boolean active = false;
	private String role = null;
	
	private static final String ROLE_MAFIA = "mafia";
  private static final String ROLE_COP = "cop";
  private static final String ROLE_OTHERS = "others";
  private Set<String> activePlayers = new HashSet<String>();
  
  //private static BufferedReader = new BufferReader(systou)
	
	public static void main(String [] args) throws IOException{
	  Client client = new Client();
	  client.startToPlay();
	}
	
	public void startToPlay() throws IOException {
		connectToServer();
		MessageType type = null;
		String target = null;
		try {			
			while (true) {
				int msgTypeCode = input.readInt();
				type = MessageType.get(msgTypeCode);
				if (type == null) {
					System.err.println("Unknown state received from server: " + msgTypeCode);
					continue;
				} 
				switch (type) {
				case CLIENT_ID_NOTIF:
				  clientName = input.readUTF();
				  printSelfTalk("My name is: " + clientName + ".");
					break;
				case EVERYONE_CLOSE_EYES:
				  printGameInstrution("Everyone close your eyes.");
				  if (active) {
				    printSelfTalk("OK, my eyes closed...");
				  }
          break;
				case EVERYONE_OPEN_EYES:
          printGameInstrution("Everyone open your eyes.");
          if (active) {
            printSelfTalk("OK, my eyes opened...");
          }
          break;
				case MAFIA_OPEN_EYES:
          printGameInstrution("Mafia, open your eyes.");
          if (role.equals(ROLE_MAFIA)) {
            printSelfTalk("OK, my eyes opened...");
          } else {
            printSelfTalk("Not my business...");
          }
          break;         
        case MAFIA_CLOSE_EYES:
          printGameInstrution("Mafia, close your eyes.");
          if (role.equals(ROLE_MAFIA)) {
            printSelfTalk("OK, my eyes closed...");
          } else {
            printSelfTalk("Not my business...");
          }
          break; 
        case COP_OPEN_EYES:
          printGameInstrution("Cop, open your eyes.");
          if (role.equals(ROLE_COP)) {
            printSelfTalk("OK, my eyes opened...");
          } else {
            printSelfTalk("Not my business...");
          }
          break;           
        case COP_CLOSE_EYES:
          printGameInstrution("Cop, close your eyes.");
          if (role.equals(ROLE_COP)) {
            printSelfTalk("OK, my eyes closed...");
          } else {
            printSelfTalk("Not my business...");
          }
          break;   
        case ROLE_NOTIF:
          System.out.println("\n\n --------- GAME STARTED ------------- \n\n");
          active = true;
          role = input.readUTF();
          switch (role) {
          case ROLE_MAFIA:
            printGameInstrution("You are mafia.");
            break;
          case ROLE_COP:
            printGameInstrution("You are cop.");
            break;
          case ROLE_OTHERS:
            printGameInstrution("You are a normal role.");
            break;
          }
          break;     
        case SYNC_PLAYER_LIST:
          activePlayers.clear();
          String names = input.readUTF();
          String[] nameArr = names.substring(1, names.length() - 1).split(",");
          for (String name : nameArr) {
            activePlayers.add(name.trim());
          }
          System.out.println("Player list: " + activePlayers);
          break;
        case MAFIA_MAKE_KILL:
          printGameInstrution("Mafia, make your kill.");
          if (active && role.equals(ROLE_MAFIA)) {
            while (true) {
              target = promptToInput("Who to kill:", activePlayers);
              if (clientName.equals(target)) {
                printRuleWarning("As a mafia, you can't kill yourself!");
                continue;
              }
              output.writeInt(MessageType.MAFIA_OFFER_KILL.val);
              output.writeUTF(target);
              break;
            }
          }
          break;
        case COP_MAKE_ACCUSE:
          printGameInstrution("Cop, make your accusation.");
          if (active && role.equals(ROLE_COP)) {
            while (true) {
              target = promptToInput("Who to accuse:", activePlayers);
              if (clientName.equals(target)) {
                printRuleWarning("You can't accuse yourself!");
                continue;
              }
              output.writeInt(MessageType.COP_OFFER_ACCUSE.val);
              output.writeUTF(target);
              break;
            }
          }
          break;
        case PUBLIC_MAKE_ACCUSE:
          printGameInstrution("Guys, make your accusation.");
          if (active) {
            while (true) {
              target = promptToInput("Who to accuse:", activePlayers);
              if (clientName.equals(target)) {
                printRuleWarning("You can't accuse yourself!");
                continue;
              }
              output.writeInt(MessageType.PUBLIC_OFFER_ACCUSE.val);
              output.writeUTF(clientName);
              output.writeUTF(target);
              break;
            }
          }          
          break;
        case COP_ACCUSE_RIGHT:
          if (active && role.equals(ROLE_COP)) {
            printGameInstrution("You made right accusation.");
          }
          break;
        case COP_ACCUSE_WRONG:
          if (active && role.equals(ROLE_COP)) {
            printGameInstrution("You made wrong accusation.");
          }
          break;
        case INFO:
          String content = input.readUTF();
          printInfo(content);
          break;
        case COP_WIN:
          active = true;
          printGameInstrution("Guys, cop won the game.");
          break;
        case MAFIA_WIN:
          active = true;
          printGameInstrution("Guys, mafia won the game.");
          break;
        case KILLED_BY_MAFIA:
          target = input.readUTF();
          activePlayers.remove(target);
          printGameInstrution("Guys, this guy was killed by mafia: " + target);
          if (clientName.equals(target)) {
            printSelfTalk("Oops, I was killed by mafia");
            active = false;
          } 
          break;
        case KILLED_BY_VOTE:
          target = input.readUTF();
          activePlayers.remove(target);
          printGameInstrution("Guys, this guy was killed by public vote: " + target);
          if (clientName.equals(target)) {
            printSelfTalk("Oops, I was killed by public vote");
            active = false;
          } 
          break;
        case GAME_RESET:
          active = true;
          printGameInstrution("Guys, the game was reset");
          break;
        
				default:
					System.out.println("Unexpected state: " + type);					
				}
			}
			
	  }	catch (IOException e) {
				System.err.println("An IOException occurred, connection reset.");
				System.exit(0);
		}
	}
  
  private void printRuleWarning(String content) {
    System.out.println("@@@ RULE VIOLATION @@@");
    System.out.println("\t" + content + "\n");
  }
	
	private void printInfo(String content) {
    System.out.println("=== INFORMATION ==");
    System.out.println("\t" + content + "\n");
	}
	
	private void printGameInstrution(String content) {
	  System.out.println(">> INSTRUCTION <<");
	  System.out.println("\t" + content);
	  
	  if (!active) {
	    System.out.println("\t !!hint: you are an observer now");
	  }
	  
	  System.out.println("");
	}
	
	private void printSelfTalk(String content) {
    System.out.println("*** " + content + " ***\n");;
	}
	
	private BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
	
	private String promptToInput(String prompt, Set<String> candidates) {
	  while (true) {
      System.out.print("## " + prompt + (candidates != null && candidates.size() > 0 ? candidates : "") + " ");
      String ret = null;
      try {
        ret = br.readLine().trim();
      } catch (IOException e) {
        e.printStackTrace();
      }
      if (ret != null) {
        ret = ret.trim();
        if (candidates == null || candidates.contains(ret)) {
          return ret;
        } else {
          System.err.println("Bad input, restricted to: " + candidates);
        }
      }      
	  }
	}
	
	private void connectToServer() throws IOException{
		address = InetAddress.getLoopbackAddress();
		client_socket = new Socket(address,port);
		input = new DataInputStream(client_socket.getInputStream()); 
		output = new DataOutputStream(client_socket.getOutputStream()); 
	}

}
