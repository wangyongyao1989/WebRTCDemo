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
                    
                    # 通知房间内其他用户（逐个容错，避免僵尸连接中断入房流程）
                    for existing_id in list(rooms[room]):
                        if existing_id != client_id:
                            try:
                                await sockets[existing_id].send(json.dumps({
                                    "eventName": "_new_peer",
                                    "data": {"socketId": client_id}
                                }))
                            except Exception as e:
                                print(f"通知 _new_peer 失败({existing_id}): {e}")
                    
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
        # 连接关闭时清理。注意：必须先从状态表中移除，再逐个通知；
        # 任何一次 send 失败（对端半死连接抛 ConnectionClosed）都不能中断清理，
        # 否则离房者会以僵尸身份残留在 rooms/sockets，导致重进房间后成员列表错乱。
        print(f"连接关闭: {client_id}")
        left_rooms = []
        # 先从所有房间中移除
        for room, room_clients in list(rooms.items()):
            if client_id in room_clients:
                room_clients.remove(client_id)
                left_rooms.append(room)
                # 通知房间内其他用户（逐个容错）
                for other_id in list(room_clients):
                    try:
                        await sockets[other_id].send(json.dumps({
                            "eventName": "_remove_peer",
                            "data": {"socketId": client_id}
                        }))
                    except Exception as e:
                        print(f"通知 _remove_peer 失败({other_id}): {e}")
                # 如果房间为空，删除房间
                if not room_clients:
                    del rooms[room]
        # 从连接列表中移除
        sockets.pop(client_id, None)

async def main():
    # 启动WebSocket服务器
    async with serve(handle_connection, "0.0.0.0", 3000):
        print("WebSocket服务器已启动，监听端口3000")
        await asyncio.Future()  # 保持服务器运行

if __name__ == "__main__":
    asyncio.run(main())
