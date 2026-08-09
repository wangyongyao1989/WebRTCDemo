import asyncio
import json
import uuid
from websockets.server import serve

# 存储连接的客户端
sockets = {}
# 存储房间信息
rooms = {}

async def handle_connection(websocket):
    # 为新连接生成唯一ID
    client_id = str(uuid.uuid4())
    sockets[client_id] = websocket
    print(f"新连接: {client_id}")
    
    try:
        async for message in websocket:
            try:
                # 解析消息
                data = json.loads(message)
                event_name = data.get('eventName')
                event_data = data.get('data', {})
                
                print(f"收到消息: {event_name} from {client_id}")
                
                if event_name == '__join':
                    # 加入房间
                    room = event_data.get('room', '__default')
                    if room not in rooms:
                        rooms[room] = []
                    
                    # 通知房间内其他用户
                    for existing_id in rooms[room]:
                        if existing_id != client_id:
                            await sockets[existing_id].send(json.dumps({
                                "eventName": "_new_peer",
                                "data": {"socketId": client_id}
                            }))
                    
                    # 将新用户加入房间
                    rooms[room].append(client_id)
                    
                    # 发送房间内现有用户列表
                    existing_connections = [id for id in rooms[room] if id != client_id]
                    await websocket.send(json.dumps({
                        "eventName": "_peers",
                        "data": {
                            "connections": existing_connections,
                            "you": client_id
                        }
                    }))
                    
                elif event_name == '__ice_candidate':
                    # 转发ICE候选
                    target_id = event_data.get('socketId')
                    if target_id in sockets:
                        await sockets[target_id].send(json.dumps({
                            "eventName": "_ice_candidate",
                            "data": {
                                "id": event_data.get('id'),
                                "label": event_data.get('label'),
                                "sdpMLineIndex": event_data.get('label'),
                                "candidate": event_data.get('candidate'),
                                "socketId": client_id
                            }
                        }))
                
                elif event_name == '__offer':
                    # 转发Offer
                    target_id = event_data.get('socketId')
                    if target_id in sockets:
                        await sockets[target_id].send(json.dumps({
                            "eventName": "_offer",
                            "data": {
                                "sdp": event_data.get('sdp'),
                                "socketId": client_id
                            }
                        }))
                
                elif event_name == '__answer':
                    # 转发Answer
                    target_id = event_data.get('socketId')
                    if target_id in sockets:
                        await sockets[target_id].send(json.dumps({
                            "eventName": "_answer",
                            "data": {
                                "sdp": event_data.get('sdp'),
                                "socketId": client_id
                            }
                        }))
                
            except json.JSONDecodeError:
                print(f"无效的JSON消息: {message}")
            except Exception as e:
                print(f"处理消息时出错: {e}")
    
    finally:
        # 连接关闭时清理
        print(f"连接关闭: {client_id}")
        # 从所有房间中移除
        for room, room_clients in list(rooms.items()):
            if client_id in room_clients:
                room_clients.remove(client_id)
                # 通知房间内其他用户
                for other_id in room_clients:
                    if other_id in sockets:
                        await sockets[other_id].send(json.dumps({
                            "eventName": "_remove_peer",
                            "data": {"socketId": client_id}
                        }))
                # 如果房间为空，删除房间
                if not room_clients:
                    del rooms[room]
        # 从连接列表中移除
        if client_id in sockets:
            del sockets[client_id]

async def main():
    # 启动WebSocket服务器
    async with serve(handle_connection, "0.0.0.0", 3000):
        print("WebSocket服务器已启动，监听端口3000")
        await asyncio.Future()  # 保持服务器运行

if __name__ == "__main__":
    asyncio.run(main())
