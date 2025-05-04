// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2021 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.tencent.yolov8ncnn;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.GridView;

import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback
{
    public static final int REQUEST_CAMERA = 100;

    private Yolov8Ncnn yolov8ncnn;
    private int facing = 0;

    private Spinner spinnerCPUGPU;
    private int current_cpugpu = 0;
    private TextView modelStatusText;
    private TextView resultText;
    private Button viewResultsBtn;
    private Button clearResultsBtn;
    private Button viewLogBtn;
    private Button clearAllBtn;

    private SurfaceView cameraView;

    private long lastSaveTime = 0;
    private static final long SAVE_INTERVAL = 1000; // 1 second in milliseconds
    private String lastDetectedCard = null;
    private static final float CONFIDENCE_THRESHOLD = 0.95f; // 置信度阈值

    private List<String> cardOrder = new ArrayList<>();
    private Set<String> cardSet = new HashSet<>();
    private static final int TOTAL_CARDS = 52;

    private CardAdapter cardAdapter;
    private GridView gridCards;
    private TextView tvCount;
    private Button btnClear;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        yolov8ncnn = new Yolov8Ncnn();
        yolov8ncnn.setActivity(this);

        cameraView = (SurfaceView) findViewById(R.id.cameraview);
        modelStatusText = (TextView) findViewById(R.id.modelStatusText);
        resultText = (TextView) findViewById(R.id.resultText);

        cameraView.getHolder().setFormat(PixelFormat.RGBA_8888);
        cameraView.getHolder().addCallback(this);

        Button buttonSwitchCamera = (Button) findViewById(R.id.buttonSwitchCamera);
        buttonSwitchCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {

                int new_facing = 1 - facing;

                yolov8ncnn.closeCamera();

                yolov8ncnn.openCamera(new_facing);

                facing = new_facing;
            }
        });

        spinnerCPUGPU = (Spinner) findViewById(R.id.spinnerCPUGPU);
        spinnerCPUGPU.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View arg1, int position, long id)
            {
                if (position != current_cpugpu)
                {
                    current_cpugpu = position;
                    reload();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0)
            {
            }
        });

        // 添加查看记录按钮
        viewResultsBtn = (Button) findViewById(R.id.viewResultsBtn);
        viewResultsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, RecordActivity.class));
            }
        });

        // 添加清空记录按钮
        clearResultsBtn = (Button) findViewById(R.id.clearResultsBtn);
        clearResultsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearResults();
            }
        });

        viewLogBtn = (Button) findViewById(R.id.viewLogBtn);
        viewLogBtn.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, LogActivity.class));
        });

        // 添加清空所有记录按钮
        clearAllBtn = (Button) findViewById(R.id.clearAllBtn);
        clearAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearAllRecords();
            }
        });

        gridCards = (GridView) findViewById(R.id.gridCards);
        tvCount = (TextView) findViewById(R.id.tvCount);
        btnClear = (Button) findViewById(R.id.btnClear);
        cardAdapter = new CardAdapter(this, cardOrder);
        gridCards.setAdapter(cardAdapter);
        btnClear.setOnClickListener(v -> clearCardOrder());

        reload();
        // 自动刷新识别记录显示
        updateResultDisplay();
    }

    private void reload()
    {
        try {
            modelStatusText.setText("模型状态: 正在加载...");
            modelStatusText.setTextColor(Color.YELLOW);
            
            // 只用 best 模型，modelid 固定为 0
            boolean ret_init = yolov8ncnn.loadModel(getAssets(), 0, current_cpugpu);
        if (!ret_init)
        {
            Log.e("MainActivity", "yolov8ncnn loadModel failed");
                modelStatusText.setText("模型状态: 加载失败");
                modelStatusText.setTextColor(Color.RED);
                return;
            }

            modelStatusText.setText("模型状态: 已加载");
            modelStatusText.setTextColor(Color.GREEN);
            yolov8ncnn.setActivity(this);
        } catch (Exception e) {
            Log.e("MainActivity", "Exception during model loading: " + e.getMessage());
            e.printStackTrace();
            modelStatusText.setText("模型状态: 加载出错");
            modelStatusText.setTextColor(Color.RED);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
    {
        yolov8ncnn.setOutputWindow(holder.getSurface());
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder)
    {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder)
    {
    }

    @Override
    public void onResume()
    {
        super.onResume();
        Log.d("MainActivity", "onResume called");
        
        try {
            yolov8ncnn.setActivity(this);
            Log.d("MainActivity", "setActivity called");

            if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED)
            {
                Log.d("MainActivity", "Requesting camera permission");
                ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
                return;
            }

            Log.d("MainActivity", "Opening camera with facing: " + facing);
            boolean ret = yolov8ncnn.openCamera(facing);
            if (!ret) {
                Log.e("MainActivity", "Failed to open camera");
                modelStatusText.setText("相机打开失败");
                modelStatusText.setTextColor(Color.RED);
            } else {
                Log.d("MainActivity", "Camera opened successfully");
                modelStatusText.setText("相机已打开");
                modelStatusText.setTextColor(Color.GREEN);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Exception during camera opening: " + e.getMessage());
            e.printStackTrace();
            modelStatusText.setText("相机打开出错: " + e.getMessage());
            modelStatusText.setTextColor(Color.RED);
        }

        // 恢复显示记录
        updateResultDisplay();
    }

    @Override
    public void onPause()
    {
        super.onPause();
        Log.d("MainActivity", "onPause called");
        
        try {
            if (yolov8ncnn != null) {
                yolov8ncnn.closeCamera();
                Log.d("MainActivity", "Camera closed");
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Exception during camera closing: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        Log.d("MainActivity", "onDestroy called");
        
        try {
            if (yolov8ncnn != null) {
                yolov8ncnn.closeCamera();
                Log.d("MainActivity", "Camera closed in onDestroy");
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Exception during cleanup: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 处理检测结果（带时间戳，供JNI调用）
    public void onDetectResult(String cardName, float confidence, long timestamp) {
        // 只记录52张且不重复
        if (cardOrder.size() >= TOTAL_CARDS) return;
        if (cardSet.contains(cardName)) return;
        cardOrder.add(cardName);
        cardSet.add(cardName);
        saveCardOrder();
        updateCardOrderDisplay();
        // 记录满52张时弹窗提示
        if (cardOrder.size() == TOTAL_CARDS) {
            runOnUiThread(() -> {
                new AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("52张牌已全部记录完成！")
                    .setPositiveButton("确定", null)
                    .show();
            });
        }
    }

    // 新增：保存cardOrder到SharedPreferences
    private void saveCardOrder() {
        SharedPreferences sp = getSharedPreferences("card_records", MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cardOrder.size(); i++) {
            sb.append(cardOrder.get(i));
            if (i != cardOrder.size() - 1) sb.append(",");
        }
        sp.edit().putString("card_order", sb.toString()).apply();
    }

    // 清空记录
    private void clearCardOrder() {
        cardOrder.clear();
        cardSet.clear();
        updateCardOrderDisplay();
    }

    // 展示牌顺序（美化版，刷新GridView和统计）
    private void updateCardOrderDisplay() {
        runOnUiThread(() -> {
            cardAdapter.notifyDataSetChanged();
            tvCount.setText(cardOrder.size() + "/52");
        });
    }

    // 保存识别结果
    private void saveResult(String cardName, float confidence, long timestamp) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSaveTime < SAVE_INTERVAL) {
            return; // Skip saving if less than 1 second has passed
        }
        lastSaveTime = currentTime;

        Log.d("MainActivity", "saveResult: " + cardName + ", " + confidence + ", " + timestamp);
        try {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date(timestamp));
            String result = String.format("%s - %s (%.1f%%)\n", time, cardName, confidence * 100);
            Log.d("MainActivity", "保存记录: " + result);
            
            SharedPreferences sp = getSharedPreferences("card_records", MODE_PRIVATE);
            String existingResults = sp.getString("results", "");
            String newResults = result + existingResults;
            sp.edit().putString("results", newResults).apply();
            Log.d("MainActivity", "saveResult写入后: " + sp.getString("results", "无"));
            
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "记录已保存", Toast.LENGTH_SHORT).show();
                updateResultDisplay();
            });
            
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                updateResultDisplay();
            });
        }
    }

    // 更新结果显示
    private void updateResultDisplay() {
        resultText.setText(""); // 始终不显示内容
    }

    // 显示所有识别记录
    private void showResults() {
        SharedPreferences sp = getSharedPreferences("card_records", MODE_PRIVATE);
        String results = sp.getString("results", "暂无识别记录");
        
        new AlertDialog.Builder(this)
            .setTitle("识别记录")
            .setMessage(results)
            .setPositiveButton("确定", null)
            .show();
    }

    // 清空所有识别记录
    private void clearResults() {
        new AlertDialog.Builder(this)
            .setTitle("确认清空")
            .setMessage("确定要清空所有识别记录吗？")
            .setPositiveButton("确定", (dialog, which) -> {
                SharedPreferences sp = getSharedPreferences("card_records", MODE_PRIVATE);
                sp.edit().putString("results", "").apply();
                resultText.setText("暂无识别记录");
                Toast.makeText(MainActivity.this, "记录已清空", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // 清空所有记录
    private void clearAllRecords() {
        new AlertDialog.Builder(this)
            .setTitle("确认清空")
            .setMessage("确定要清空所有识别记录和错误日志吗？")
            .setPositiveButton("确定", (dialog, which) -> {
                // 清空识别记录
                SharedPreferences sp1 = getSharedPreferences("card_records", MODE_PRIVATE);
                sp1.edit().putString("results", "").apply();
                
                resultText.setText("暂无识别记录");
                Toast.makeText(MainActivity.this, "所有记录已清空", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }
}
