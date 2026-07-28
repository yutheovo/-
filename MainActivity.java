package com.theo.toycontrol;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback currentCallback;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Random random = new Random();
    private TextView statusText;
    private int currentLevel = 0;

    // 强度字节：7档从低到高，0x33到0x64
    private static final int[] LEVELS = {0x33, 0x3c, 0x45, 0x4b, 0x55, 0x5f, 0x64};
    private static final String[] LEVEL_NAMES = {"1档", "2档", "3档", "4档", "5档", "6档", "7档"};

    // 固定头部（从日志分析）
    private static final byte[] FIXED_HEADER = {0x02};
    private static final byte[] FIXED_TAIL = {0x00, 0x00, 0x64, 0x00, 0x01, 0x53, 0x23, 0x00};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);

        // 初始化蓝牙
        BluetoothManager btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter btAdapter = btManager.getAdapter();

        if (btAdapter == null || !btAdapter.isEnabled()) {
            statusText.setText("请先开启蓝牙");
            return;
        }

        advertiser = btAdapter.getBluetoothLeAdvertiser();

        // 请求权限
        requestPermissions();

        // 设置按钮
        int[] btnIds = {R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4, R.id.btn5, R.id.btn6, R.id.btn7};
        for (int i = 0; i < btnIds.length; i++) {
            final int level = i;
            Button btn = findViewById(btnIds[i]);
            btn.setOnClickListener(v -> sendCommand(level));
        }

        Button stopBtn = findViewById(R.id.btnStop);
        stopBtn.setOnClickListener(v -> sendStop());
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            }, 1);
        }
    }

    private byte[] buildPayload(int levelIndex) {
        // 格式: [随机2字节] [02] [强度byte] [00 00 64 00] [01 53 23 00]
        byte[] payload = new byte[12];
        payload[0] = (byte) (random.nextInt(256));
        payload[1] = (byte) (random.nextInt(256));
        payload[2] = 0x02;
        payload[3] = (byte) LEVELS[levelIndex];
        payload[4] = 0x00;
        payload[5] = 0x00;
        payload[6] = 0x64;
        payload[7] = 0x00;
        payload[8] = 0x01;
        payload[9] = 0x53;
        payload[10] = 0x23;
        payload[11] = 0x00;
        return payload;
    }

    private byte[] buildStopPayload() {
        // 停止: [随机2字节] [00 00 00 00 00 00 00 00] [01 53 23 00]
        byte[] payload = new byte[12];
        payload[0] = (byte) (random.nextInt(256));
        payload[1] = (byte) (random.nextInt(256));
        // bytes 2-7 全0
        payload[8] = 0x01;
        payload[9] = 0x53;
        payload[10] = 0x23;
        payload[11] = 0x00;
        return payload;
    }

    private void sendCommand(int levelIndex) {
        if (advertiser == null) {
            Toast.makeText(this, "蓝牙不可用", Toast.LENGTH_SHORT).show();
            return;
        }

        // 先停止之前的广播
        stopAdvertising();

        byte[] payload = buildPayload(levelIndex);
        currentLevel = levelIndex;
        startAdvertising(payload);
        statusText.setText("当前：" + LEVEL_NAMES[levelIndex]);
    }

    private void sendStop() {
        stopAdvertising();
        byte[] payload = buildStopPayload();
        // 发送停止指令，广播一次后停止
        startAdvertisingOnce(payload);
        statusText.setText("已停止");
        currentLevel = 0;
    }

    private void startAdvertising(byte[] payload) {
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build();

        // 厂商ID从日志里找到的
        int manufacturerId = 0x0708;

        AdvertiseData data = new AdvertiseData.Builder()
            .addManufacturerData(manufacturerId, payload)
            .build();

        currentCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                // 广播成功
            }
            @Override
            public void onStartFailure(int errorCode) {
                runOnUiThread(() -> statusText.setText("广播失败: " + errorCode));
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            advertiser.startAdvertising(settings, data, currentCallback);
        }
    }

    private void startAdvertisingOnce(byte[] payload) {
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build();

        int manufacturerId = 0x0708;

        AdvertiseData data = new AdvertiseData.Builder()
            .addManufacturerData(manufacturerId, payload)
            .build();

        AdvertiseCallback cb = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings s) {
                // 500ms后停止广播
                handler.postDelayed(() -> {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                            || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        advertiser.stopAdvertising(this);
                    }
                }, 500);
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            advertiser.startAdvertising(settings, data, cb);
        }
    }

    private void stopAdvertising() {
        if (currentCallback != null && advertiser != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                advertiser.stopAdvertising(currentCallback);
            }
            currentCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sendStop();
    }
}
