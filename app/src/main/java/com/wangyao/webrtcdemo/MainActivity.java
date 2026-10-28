package com.wangyao.webrtcdemo;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 入口：填写信令服务器地址与房间号，进入通话界面。
 *
 * 默认服务器地址为 ws://10.0.2.2:3000（对应模拟器访问宿主机上运行的 server.py）。
 * 真机测试时请改为宿主机局域网 IP，例如 ws://192.168.1.100:3000。
 * 上次成功填写的地址/房间号会自动记忆（IP 漂移后无需每次重填）。
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "call_prefs";
    private static final String KEY_SERVER = "server_url";
    private static final String KEY_ROOM = "room_id";

    private EditText etServer;
    private EditText etRoom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServer = findViewById(R.id.et_server);
        etRoom = findViewById(R.id.et_room);
        Button btnJoin = findViewById(R.id.button);

        // 回填上次使用的地址/房间号
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String lastServer = prefs.getString(KEY_SERVER, null);
        String lastRoom = prefs.getString(KEY_ROOM, null);
        if (!TextUtils.isEmpty(lastServer)) etServer.setText(lastServer);
        if (!TextUtils.isEmpty(lastRoom)) etRoom.setText(lastRoom);

        btnJoin.setOnClickListener(v -> joinRoom());
    }

    private void joinRoom() {
        String server = etServer.getText().toString().trim();
        String room = etRoom.getText().toString().trim();
        if (TextUtils.isEmpty(server)) {
            Toast.makeText(this, "请填写服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(room)) {
            Toast.makeText(this, "请填写房间号", Toast.LENGTH_SHORT).show();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_SERVER, server).putString(KEY_ROOM, room).apply();
        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra(CallActivity.EXTRA_SERVER_URL, server);
        intent.putExtra(CallActivity.EXTRA_ROOM_ID, room);
        startActivity(intent);
    }
}
