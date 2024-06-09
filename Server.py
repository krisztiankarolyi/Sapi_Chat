import json
import random
import socket
import threading
import datetime
import time
import traceback
import mysql.connector
import re

class Message:
    def __init__(self, message_type, content, timestamp ,sender, receiver):
        self.message_type = message_type
        self.content = content
        self.timestamp = timestamp
        self.sender = sender
        self.receiver = receiver

    def __str__(self):
        return f"Message Type: {self.message_type}, Content: {self.content}, Timestamp: {self.timestamp},  Sender: {self.sender},  Receiver: {self.receiver}"

    def to_json(self):
        return json.dumps({
            "message_type": self.message_type,
            "content": self.content,
            "timestamp": self.timestamp.strftime('%H:%M:%S'),
            "sender": self.sender,
            "receiver": self.receiver
        })
    
    @classmethod
    def from_json(cls, json_data):
        message_type = json_data.get("message_type")
        content = json_data.get("content")
        timestamp = datetime.datetime.strptime(json_data.get("timestamp"), '%H:%M:%S')
        sender = json_data.get("sender")
        receiver = json_data.get("receiver")

        return cls(message_type, content, timestamp, sender, receiver)

    
class User:
    def __init__(self, name, conn):
        self.name = name
        self.conn = conn

    def send_message(self, message: Message):
        if (isinstance(message, Message)):
            message = message.to_json()

        if self.conn and self.conn.fileno() != -1:
            self.conn.send(message.encode('utf-8'))


class MessageRouter:
    sqlConnection = ""
    cursor = ""
    messages = []

    def __init__(self, max_messages, history_limit):
        self.users = []
        self.messages = []
        self.history_limit = history_limit
        self.max_messages = max_messages  # Új paraméter hozzáadása
        self.connectSQL()
        self.loadMessagesFromDatabase()  
    
    def connectSQL(self):
        try:
            self.sqlConnection = mysql.connector.connect(
            host="localhost",
            user="root",
            password="",
            database="chatServer" )

            if(self.sqlConnection):
                self.cursor = self.sqlConnection.cursor()
                print("A MySQL adatbázisra sikeresen csatlakozva - üzenetek mentése menni fog. ")
        except:
            print("A MySQL adatbázisra való csatlakozás sikertelen - az üzenetek nem lesznek elmentve! ")

    def loadMessagesFromDatabase(self):
        if self.cursor != "":
            sql = f"SELECT type, content, sender, receiver, time FROM messages ORDER BY time DESC LIMIT {self.history_limit}"
            self.cursor.execute(sql)
            rows = self.cursor.fetchall()
            for msg in rows:
                msg = Message(msg[0], msg[1], msg[4], msg[2], msg[3])
                if msg.message_type == 'public' and msg.sender != msg.content:
                    self.messages.append(msg)
            print("Üzenetek beolvasva az adatbázisból")

    def loadPreviousMessages(self, user):
        user.send_message(Message("private", "Loading previous messages...",  datetime.datetime.now(), "server", user.name))
        for msg in self.messages:
            time.sleep(0.05)
            user.send_message(msg)
        print("Régebbi üzenetek elküldve "+ user.name+" felé ("+str(len(self.messages))+")")  

    def add_user(self, user: User):
        try:
            self.users.append(user)
        except Exception as e:
            print(e)

    def broadcast_message(self, message: Message):
        for user in self.users:
            try:
                user.send_message(message)
                if message.message_type != "server" and message.sender != 'server' and message.message_type != "user_info" and message.sender != message.content:
                    self.messages.append(message)  
                    self.saveMessageToDatabase(message)  

            except Exception as ex:
                print(ex)

    def saveMessageToDatabase(self, message: Message):
        if self.cursor != "":
            # Először töröld a régi üzeneteket, ha túl sok van
            self.trimOldMessages()
            
            # Most már hozzáadhatod az új üzenetet
            sql = "INSERT INTO messages (type, content, sender, receiver) VALUES (%s, %s, %s, %s)"
            values = (message.message_type, message.content, message.sender, message.receiver)
            self.cursor.execute(sql, values)
            self.sqlConnection.commit()

    def trimOldMessages(self):
        if len(self.messages) > self.max_messages:
            self.messages = self.messages[-self.max_messages:]
            # Töröld az összes üzenetet az adatbázisból
            self.cursor.execute("DELETE FROM messages")
            self.sqlConnection.commit()
            # Most pedig mentsd el csak a legutolsó üzeneteket
            for msg in self.messages:
                self.saveMessageToDatabase(msg)

    def private_message(self, message: Message):
        success = False
       
        for user in self.users:
            try:
                if user.name == message.receiver:
                    user.send_message(message)
                    success = True

                if user.name == message.sender and success:
                    user.send_message(Message("SERVER", "A privát üzenet sikeresen kézbesítve", datetime.datetime.now(), "[SZERVER]", message.sender))
            except Exception as e:
                print(e)

        if not success:
            senderName = message.sender
            sender = self.getUserByName(senderName)
            error_message = Message("private",
                                    "Hiba: a címzett ("+message.receiver+
                                    ") nem található!", datetime.datetime.now(), "[SZERVER]", senderName)
            sender.send_message(error_message)

    def getUserByName(self, name)-> User:
        for user in self.users:
            if user.name == name:
                return user

    def remove_user(self, user):
        try:
            if user in self.users:
                self.users.remove(user)
        except Exception as e:
            print(e)


def handle_client(conn, addr, router):
    print(f"[NEW CONNECTION] {addr} connected.")
    user = None
    try:
        while True:
            data = conn.recv(1024).decode('utf-8')
            if not data:
                break

            for raw_msg in data.split("\n"):
                if not raw_msg.strip():
                    continue

                msg = json.loads(raw_msg)
                msg = Message.from_json(msg)

                if not user:              
                    if msg.message_type == "user_info":
                        if any(existing_user.name == msg.content for existing_user in router.users):
                            msg.content += str(random.randint(100, 999))

                        name = re.sub(r'[^a-zA-Z0-9 ]', '', msg.content)
                        user = User(name, conn)
                        router.add_user(user)
                        welcome_message = Message('server', user.name +" csatlakozott. Üdv, "+user.name+"!", datetime.datetime.now(), 'server', user.name)
                        router.broadcast_message(welcome_message)
                        print(f"{user.name} has been authenticated")
                        router.loadPreviousMessages(user)

                    else:
                        print(f"Message from unauthenticated user")
                        authentication_required_message = Message('server', "Autentikálja magát először!", datetime.datetime.now(), 'server', 'unknown')
                        conn.send(authentication_required_message.to_json().encode('utf-8'))
                        conn.close()
                        continue

                else:

                    if msg.content == '@exit':
                        handle_user_disconnect(user, conn, router)
                        break

                    if msg.message_type == 'private':           
                        if msg.sender != msg.receiver:
                            router.private_message(msg)
                        else:
                            router.private_message(Message("private",
                                        "Hiba: Magadnak nem küldhetsz privát üzenetet! :) ", datetime.datetime.now(), "[SZERVER]", msg.sender))

                    elif msg.content == "@users":
                        active_users = [user.name for user in router.users]
                        list_message = Message('server', f'Aktív felhasználók: {", ".join(active_users)}',datetime.datetime.now() , 'server', user.name)
                        user.send_message(list_message)

                    elif msg.content.startswith('@newName'):
                        new_name = msg.content.split(' ', 1)[1]
                        handle_user_name_change(user, new_name, router)
                    else:
                        broadcast_message = Message('public', msg.content, msg.timestamp, user.name, 'all')
                        router.broadcast_message(broadcast_message)
                

    except json.decoder.JSONDecodeError as ex:
        print(f"Hibás JSON üzenet érkezett: {data} \n {msg}")
        print(ex)
        traceback.print_exc()

    except Exception as e:
        print(f"[ERROR] Hiba a kapcsolat kezelése közben: {e} ")
    
    finally:
        handle_user_disconnect(user, conn, router)
        print(f"[CONNECTION CLOSED] {addr} kibannolva.")

                   
       
def handle_user_disconnect(user, conn, router):
    if user:
        disconnect_message = Message('server', f'<{user.name}> kilépett.', datetime.datetime.now(), 'server', user.name)
        router.remove_user(user)
        conn.close()
        router.broadcast_message(disconnect_message.to_json())

def handle_user_name_change(user, new_name, router):
    if not any(existing_user.name == new_name for existing_user in router.users):
        old_name = user.name
        user.name = new_name
        success_message = Message('server', f'{old_name} felhasználóneve megváltozott erre: {new_name}', datetime.datetime.now(), 'server', new_name)
        router.broadcast_message(success_message)
        print(f"{old_name} has changes his/her name to {new_name}")
    else:
        error_message = Message('server', 'Ez a név már foglalt. Válassz másikat!', datetime.datetime.now(), 'server', user.name)
        user.send_message(error_message)

def start_server(host, port, max_messages, history_limit):
    try:
        router = MessageRouter(max_messages, history_limit)
        server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server_socket.bind((host, port))
        server_socket.listen()
        ip = socket.gethostbyname(socket.gethostname())
        print(f"Chat szerver elindult a(z) {ip}:{port} címen.")

        while True:
            client_socket, client_address = server_socket.accept()
            client_thread = threading.Thread(target=handle_client, args=(client_socket, client_address, router))
            client_thread.start()
    except Exception as e:
        print(e)

if __name__ == "__main__":
    start_server("0.0.0.0", 400, 10, 10)
