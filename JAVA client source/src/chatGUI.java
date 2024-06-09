import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import javax.json.*;
import javax.swing.text.*;

import net.miginfocom.swing.MigLayout;

public class chatGUI  extends JFrame{

    private DefaultListModel<String> userListModel;
    private JList<String> userList;

    public  boolean connected = false;
    private String userName = "";
    private JTextField messageField;
    private JTextPane chatArea;
    private Socket clientSocket;

    private Font font = new Font("MONOSPACED", Font.PLAIN, 16);


    private void connect() {
        boolean connected = false;

        while (!connected) {
            try {
                String ip = JOptionPane.showInputDialog("Kérem adja meg az IP-címet:");
                if (ip == null) {
                    System.exit(0);
                }

                String portStr = JOptionPane.showInputDialog("Kérem adja meg a portot:");
                if (portStr == null) {
                    System.exit(0);
                }

                try {
                    int port = Integer.parseInt(portStr);
                    Socket clientSocket = new Socket(ip, port);
                    connected = clientSocket.isConnected();
                    if (connected) {
                        this.clientSocket = clientSocket;
                        startClient(clientSocket);

                    }

                } catch (NumberFormatException e) {
                    JOptionPane.showMessageDialog(null, "Hibás portszám formátum!", "Error", JOptionPane.WARNING_MESSAGE);
                }
            } catch (Exception exp) {
                JOptionPane.showMessageDialog(null, exp.getLocalizedMessage(), "Error", JOptionPane.WARNING_MESSAGE);
            }
        }
    }


    public chatGUI() {
        connect();
        initUI();
        startClient(clientSocket);
        requestUserList(); /* ha itt meghivom hogy automatiksuan megjelenjen belepskor a user lista, akkor kivetelt dob a szerver */

    }

    private void initUI() {
        setTitle("Chat Client (Beta)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setMinimumSize(new Dimension(800, 600));
        setLocationRelativeTo(null);
        getContentPane().setBackground(Color.DARK_GRAY);
        getContentPane().setForeground(Color.WHITE);
        getContentPane().setFont(font);

        messageField = new JTextField();
        chatArea = new JTextPane();
        chatArea.setEditable(false);
        messageField.setBackground(Color.DARK_GRAY);
        chatArea.setBackground(Color.DARK_GRAY);
        messageField.setForeground(Color.WHITE);
        chatArea.setForeground(Color.WHITE);
        chatArea.setFont(font);
        messageField.setFont(font);

        ImageIcon sendIcon = new ImageIcon(getClass().getResource("/res/send.png"));
        JButton sendButton = new JButton("", sendIcon);
        sendButton.setBackground(Color.DARK_GRAY);
        sendButton.setBorder(null);

        sendButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if(!messageField.getText().trim().equals(""))
                    sendMessageFromTextField();
            }
        });

        messageField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if(!messageField.getText().trim().equals(""))
                         sendMessageFromTextField();
                }
            }
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (clientSocket != null && clientSocket.isConnected()) {
                    try {
                        clientSocket.close();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                }
                System.exit(0);
            }
        });

        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setBackground(Color.DARK_GRAY);
        userList.setForeground(Color.white);
        userList.setFont(font);

        JScrollPane userListScrollPane = new JScrollPane(userList);

        JButton refreshButton = new JButton("Refresh users list");
        refreshButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                requestUserList();
            }
        });

        userList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 1) {
                    JList<String> list = (JList<String>) e.getSource();
                    String selectedUser = list.getSelectedValue();
                    if (selectedUser != null && messageField != null) {
                        messageField.setText(String.format("@private %s -> ", (selectedUser).trim()));
                    }
                }
            }
        });

        setLayout(new MigLayout("fill", "[90%][10%]", "[90%, grow][10%, grow]"));

        add(new JScrollPane(chatArea), "cell 0 0, grow");
        add(userListScrollPane, "cell 1 0, grow");
        add(messageField, "cell 0 1, grow");
        add(sendButton, "cell 1 1, grow");
        add(refreshButton, "cell 1 1, grow");

    }

    private  void requestUserList() {
        sendMessage(clientSocket, "server", "@users", userName, "server");
    }

    private void updateUsersList(String users) {
        try{
            SwingUtilities.invokeLater(() -> {
                if(userListModel != null){
                    userListModel.clear();
                    String[] userArray = users.split(":");

                    if (userArray.length > 1)
                    {
                         userArray = userArray[1].split(",");
                         for (String user : userArray)
                         {
                             userListModel.addElement(user);
                         }
                    }
                }
            });
        }
        catch(Exception ex){
            JOptionPane.showMessageDialog(null, ex.getLocalizedMessage(), "Error", JOptionPane.WARNING_MESSAGE);
        }

    }

    private void startClient(Socket clientSocket) {
        try {
            while(userName.replace(" ", "").equals(""))
            {
                userName = JOptionPane.showInputDialog(this, "Kérjük, adja meg a nevét:", "Bejelentkezés", JOptionPane.PLAIN_MESSAGE);

            }
            sendMessage(clientSocket, "user_info", userName, userName, "server");
            setTitle("Chat Client (Beta) - logged in as "+userName);
            Thread receiveThread = new Thread(() -> receiveMessages(clientSocket));
            receiveThread.start();
            connected = true;


        }
        catch (Exception e){
            JOptionPane.showMessageDialog(null, e.getLocalizedMessage(), "Error", JOptionPane.WARNING_MESSAGE);
        }
    }


    private void receiveMessages(Socket clientSocket) {
        try {
            BufferedInputStream inputStream = new BufferedInputStream(clientSocket.getInputStream());

            while (true) {
                byte[] buffer = new byte[1024];
                int bytesRead = inputStream.read(buffer);

                if (bytesRead == -1) {
                    appendToChatArea("A kapcsolat megszakadt / sikertelen kapcsolódás.");
                    int userChoice = JOptionPane.showConfirmDialog(
                            this,
                            "A kapcsolat megszakadt / sikertelen kapcsolódás. Szeretné újraindítani az alkalmazást?",
                            "Kapcsolat hiba",
                            JOptionPane.YES_NO_OPTION);

                    if (userChoice == JOptionPane.YES_OPTION) {
                        restartApplication();
                    }
                    break;
                }

                String jsonMessage = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);


                SwingUtilities.invokeLater(() -> processReceivedJsonMessage(jsonMessage));
            }
        } catch (IOException e) {
            appendToChatArea("Hiba az üzenetek fogadása közben.");
            int userChoice = JOptionPane.showConfirmDialog(
                    this,
                    "Szeretné újraindítani az alkalmazást?",
                    "Kapcsolat hiba",
                    JOptionPane.YES_NO_OPTION);

            if (userChoice == JOptionPane.YES_OPTION) {
                restartApplication();
            }
            e.printStackTrace();
        }
    }

    private void processReceivedJsonMessage(String jsonMessage) {
        try (JsonReader reader = Json.createReader(new StringReader(jsonMessage))) {
            JsonObject jsonObject = reader.readObject();
            String messageType = jsonObject.getString("message_type");
            String sender = jsonObject.getString("sender");
            String content = jsonObject.getString("content");
            String time = jsonObject.getString("timestamp");

            if ("server".equals(messageType) || "server".equals(sender)) {
                    updateUsersList(content);
                    appendToChatArea("[SZERVER] | " + time + ": " + content, Color.orange);
                }


            else if ("public".equals(messageType))
            {
                appendToChatArea("<" + sender + "> | " + time + ": " + content);
            }
            else if ("private".equals(messageType)) {
                appendToChatArea("[Privát] küldte: <" + sender + "> | " + time + ": " + content, Color.red);
            }
        } catch (Exception e) {
            appendToChatArea("Hiba a JSON üzenet feldolgozása során.");
            System.out.println("Received JSON: " + jsonMessage);
            e.printStackTrace();
        }
    }

    public void restartApplication()
    {
        try
        {
            final String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            final File currentJar = new File(chatGUI.class.getProtectionDomain().getCodeSource().getLocation().toURI());

            if(!currentJar.getName().endsWith(".jar"))
                return;

            final ArrayList<String> command = new ArrayList<String>();
            command.add(javaBin);
            command.add("-jar");
            command.add(currentJar.getPath());
            final ProcessBuilder builder = new ProcessBuilder(command);
            builder.start();
            clientSocket.close();
            System.exit(0);
        }
        catch (Exception ex){
            JOptionPane.showMessageDialog(null, ex.getLocalizedMessage(), "Error", JOptionPane.WARNING_MESSAGE);
        }
    }


    private void sendMessageFromTextField() {
        String message = "";
        if(messageField != null){
             message = messageField.getText().toString();
        }

        if(message.toLowerCase().equals("@restart") || message.toLowerCase().equals("@reconnect")){
            restartApplication();
        }

        else if(message.toLowerCase().startsWith("@private"))
        {
            String[] parts = message.split(" ", 4);

            if (parts.length == 4) {
                String receiver = parts[1];
                String content = parts[3];
                appendToChatArea(String.format("[Privát]: Te --> %s: %s", receiver, content));
                sendMessage(clientSocket, "private", content, userName, receiver);
            }
            else {
                appendToChatArea("Hibás privát üzenet formátum. Használd a következőt: @private [címzett] -> [üzenet]");
            }
        }

        else if(message.toLowerCase().equals("@help")){
            printAvailableCommands();
        }

        else {
            sendMessage(clientSocket, "public", message, userName, "all");
        }
        if(messageField != null)
         messageField.setText("");

    }

    private String readLine(BufferedReader reader) throws IOException
    {
        return reader.readLine();
    }

    public  void printAvailableCommands()
    {
        appendToChatArea("Elérhető parancsok:");
        appendToChatArea("@exit - Kilépés a chatről+ \n @help - Elérhető parancsok listázása \n @users - Aktív felhasználók listázása \n @newName [új név] - Felhasználónév megváltoztatása \n @exit - Kilépés \n @restart - újraindítás ");

    }


    private void appendToChatArea( String msg)
    {
        Color c = Color.white;
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = chatArea.getStyledDocument();
            SimpleAttributeSet style = new SimpleAttributeSet();
            StyleConstants.setForeground(style, c);
            StyleConstants.setFontFamily(style, "Lucida Console");
            StyleConstants.setAlignment(style, StyleConstants.ALIGN_JUSTIFIED);

            try {
                doc.insertString(doc.getLength(), msg+"\n", style);
                chatArea.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        });
    }
    private void appendToChatArea(String msg, Color c) {
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = chatArea.getStyledDocument();
            SimpleAttributeSet style = new SimpleAttributeSet();
            StyleConstants.setForeground(style, c);
            StyleConstants.setFontFamily(style, "Lucida Console");
            StyleConstants.setAlignment(style, StyleConstants.ALIGN_JUSTIFIED);

            try {
                doc.insertString(doc.getLength(), msg+"\n", style);
                chatArea.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        });
    }

    private void sendMessage(Socket clientSocket, String messageType, String content, String sender, String receiver) {
        try {
            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
            String timestamp = now.format(formatter);
            String message = String.format("{\"message_type\":\"%s\",\"content\":\"%s\",\"timestamp\":\"%s\",\"sender\":\"%s\",\"receiver\":\"%s\"}",
                    messageType, content, timestamp, sender, receiver);

            if (content.contains("@newName")) {
                userName = content.split("@newName")[1];
                setTitle("Chat Client (Beta) - logged in as "+userName);
            }

            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
            out.println(message);

            if (content.equals("@exit")) {
                appendToChatArea("Kiléptél a chatből.");
                clientSocket.close();
                System.exit(0);
            }
            if (messageField != null) {
                messageField.setText("");
            }

        } catch (IOException e) {
            appendToChatArea("Hiba az üzenet küldése során.");
            e.printStackTrace();
        }
    }


    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                JFrame frame = new chatGUI();
                frame.setVisible(true);
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            }
        });
    }
}



