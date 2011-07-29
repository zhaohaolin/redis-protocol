package redis;

import com.google.common.base.Charsets;
import com.sampullara.cli.Args;
import com.sampullara.cli.Argument;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis server
 * User: sam
 * Date: 7/28/11
 * Time: 1:40 PM
 * To change this template use File | Settings | File Templates.
 */
public class RedisServer {

  public static Logger logger = Logger.getLogger("RedisServer");

  @Argument(alias = "p")
  private static Integer port = 6379;

  @Argument(alias = "pw")
  private static String password;

  protected static String auth;

  private static Map<String, MethodHandle> commands = new HashMap<>();

  public static void main(String[] args) throws IOException, IllegalAccessException {
    try {
      Args.parse(RedisServer.class, args);
    } catch (IllegalArgumentException e) {
      Args.usage(RedisServer.class);
      System.exit(1);
    }
    init();

    ExecutorService es = Executors.newCachedThreadPool();
    ServerSocket ss = new ServerSocket(port);
    logger.info("Listening");
    while (true) {
      final Socket accept = ss.accept();
      es.execute(new ServerConnection(accept));
      logger.info("Client connected");
    }
  }

  interface Code {
    Reply call(byte[][] arguments);
  }

  private static Map<String, Database> databases = new ConcurrentHashMap<>();

  private static void init() throws IllegalAccessException {
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    for (Method method : Database.class.getMethods()) {
      commands.put(method.getName(), lookup.unreflect(method));
    }
  }

  private static class ServerConnection implements Runnable {
    private final Socket accept;
    private Database database;

    public ServerConnection(Socket accept) {
      this.accept = accept;
      database = databases.get("0");
      if (database == null) {
        databases.put("0", database = new Database());
      }
    }

    private Reply execute(Command command) {
      byte[][] arguments = command.getArguments();
      String verb = new String(arguments[0], Charsets.UTF_8).toLowerCase();
      if (!"auth".equals(verb)) {
        if (password != null && !password.equals(auth)) {
          return new Reply.ErrorReply("Not authenticated");
        }
      }
      MethodHandle code = commands.get(verb);
      if (code == null) {
        return new Reply.ErrorReply("Command not implemented or invalid arguments: " + verb);
      }
      try {
        return (Reply) code.invoke(database, arguments);
      } catch (Throwable throwable) {
        logger.log(Level.SEVERE, "Failed", throwable);
        return new Reply.ErrorReply("Failed: " + throwable);
      }
    }

    public void run() {
      try {
        RedisProtocol rp = new RedisProtocol(accept);
        while (true) {
          Command command = rp.receive();
          byte[][] arguments = command.getArguments();
          if ("select".equals(new String(arguments[0], Charsets.UTF_8).toLowerCase())) {
            String name = new String(arguments[1], Charsets.UTF_8);
            database = databases.get(name);
            if (database == null) {
              databases.put(name, database = new Database());
            }
          }
          Reply execute = execute(command);
          if (execute == null) {
            break;
          } else {
            rp.send(execute);
          }
        }
      } catch (IOException e) {
        logger.log(Level.WARNING, "Disconnected abnormally");
      } finally {
        logger.info("Client disconnected");
        try {
          accept.close();
        } catch (IOException e1) {
          // ignore
        }
      }
    }
  }
}
