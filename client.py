import datetime
import json
import socket
import threading
import traceback
user_name = ""

def print_available_commands():
    print("Elérhető parancsok:")
    print("@exit - Kilépés a chatről")
    print("@help - Elérhető parancsok listázása")
    print("@private [címzett] -> [üzenet] - Privát üzenet küldése")
    print("@users - Aktív felhasználók listázása")
    print("@newName [új név] - Felhasználónév megváltoztatása")

def send_message(client_socket, message_type, content, sender, receiver):
    message = json.dumps({
        "message_type": message_type,
        "content": content,
        "sender": sender,
        "timestamp": str(datetime.datetime.now().strftime('%H:%M:%S')),
        "receiver": receiver
    })

    client_socket.send(message.encode('utf-8'))

def receive_messages(client_socket):
    while True:
        try:
            message = json.loads(client_socket.recv(1024).decode('utf-8'))
            message_type = message["message_type"]
            sender = message["sender"]
            content = message["content"]
            time = message["timestamp"]
            
            if message_type == "server":
                # Szerver üzenete
                print(f"\x1b[33m[SZERVER] | {time}: {content}  \x1b[0m")

            elif message_type == "public":
                # Közönséges üzenet
                print(f"<{sender}> | {time}: {content}")

            elif message_type == "private":
                # Privát üzenet
                print(f"\x1b[31m[Privát] küldte: <{sender}> | {time}: {content}\x1b[0m")

        except Exception as e:
            print("A kapcsolat megsezakadt.")
            print(e)
            print(traceback.format_exc())
            break


def send_messages(client_socket):
    while True:
        message = input()
        if message.lower() == "@exit":
            send_message(client_socket, "server", "@exit", user_name, "all")
            client_socket.close()
            print("\x1b[34mKiléptél a chat-ből.\x1b[0m")
            break

        elif message.lower() == "@help":
            print_available_commands()

        elif message.startswith("@private"):
            # Kinyerjük a címzett nevét és az üzenet tartalmát
            parts = message.split(" ", 3)
            if len(parts) == 4:
                receiver = parts[1]
                content = parts[3]
                print(f"\x1b[34m[Privát]: Te --> {receiver}: {content}\x1b[0m")
                send_message(client_socket, "private", content, user_name, receiver)
            else:
                print("Hibás privát üzenet formátum. Használd a következőt: @private [címzett] -> [üzenet]")

        else:
            send_message(client_socket, "public", message, user_name, "all")

def start_client(host, port):
    try:
        global user_name
        client_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client_socket.connect((host, port))

        print("\x1b[34mKérjük, adja meg a nevét:\x1b[0m")
        user_name = input()
        send_message(client_socket, "user_info", user_name, user_name, "server" ) 

        receive_thread = threading.Thread(target=receive_messages, args=(client_socket,))
        send_thread = threading.Thread(target=send_messages, args=(client_socket,))

        receive_thread.start()
        send_thread.start()

    except Exception as e:
        print("A kapcsolata megszakadt.")
        print(e)
        print(traceback.format_exc())


if __name__ == "__main__":
    host = input("Kérem a kiszolgáló IPv4 címét:\n")
    port = int(input("Kérem a portot:\n"))
    start_client(host, port)
