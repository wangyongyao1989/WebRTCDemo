#!/usr/bin/env python3
"""通过 Chrome DevTools Protocol 驱动 browser-peer.html（弱网测试自动化用）。

用法:
  python3 cdp-drive.py eval   "<JS表达式>"      # 在测试页执行 JS 并打印返回值
  python3 cdp-drive.py join   <房间号> [ws地址]  # 填表并点击加入房间
  python3 cdp-drive.py hangup                    # 挂断
  python3 cdp-drive.py status                    # 打印 status/badge/stats/log 尾部

Chrome 需以 --remote-debugging-port=9222 启动。
"""
import asyncio
import json
import os
import sys
import urllib.request

CDP = os.environ.get("CDP_URL", "http://127.0.0.1:9222")
PAGE_MATCH = "browser-peer.html"


def find_page_ws():
    with urllib.request.urlopen(CDP + "/json") as r:
        targets = json.load(r)
    for t in targets:
        if t.get("type") == "page" and PAGE_MATCH in t.get("url", ""):
            return t["webSocketDebuggerUrl"]
    raise SystemExit("未找到 browser-peer.html 页面，请确认 Chrome 已启动并打开该页")


async def cdp_eval(expr, timeout=30):
    import websockets

    ws_url = find_page_ws()
    async with websockets.connect(ws_url, max_size=10 * 1024 * 1024) as ws:
        await ws.send(json.dumps({
            "id": 1, "method": "Runtime.evaluate",
            "params": {"expression": expr, "returnByValue": True, "awaitPromise": True},
        }))
        while True:
            msg = json.loads(await asyncio.wait_for(ws.recv(), timeout))
            if msg.get("id") == 1:
                res = msg.get("result", {}).get("result", {})
                if "value" in res:
                    v = res["value"]
                    return v if isinstance(v, str) else json.dumps(v, ensure_ascii=False, indent=2)
                return res.get("description", str(msg))


JS_JOIN = """(async () => {{
  document.getElementById('server').value = {ws!r};
  document.getElementById('room').value = {room!r};
  await join();
  return 'join called';
}})()"""

JS_STATUS = """(() => JSON.stringify({
  status: document.getElementById('status').textContent,
  badge: document.getElementById('mediaBadge').textContent,
  stats: document.getElementById('stats').textContent,
  logTail: document.getElementById('log').textContent.split('\\n').slice(-12).join('\\n')
}, null, 2))()"""


def main():
    os.environ.setdefault("NO_PROXY", "*")
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "eval":
        expr = sys.argv[2]
    elif cmd == "join":
        room = sys.argv[2] if len(sys.argv) > 2 else "888123"
        ws_addr = sys.argv[3] if len(sys.argv) > 3 else "ws://127.0.0.1:3000"
        expr = JS_JOIN.format(ws=ws_addr, room=room)
    elif cmd == "hangup":
        expr = "(() => { hangup(); return 'hung up'; })()"
    elif cmd == "status":
        expr = JS_STATUS
    else:
        raise SystemExit(__doc__)
    print(asyncio.run(cdp_eval(expr)))


if __name__ == "__main__":
    main()
