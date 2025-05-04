package com.tencent.yolov8ncnn;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

public class LogActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        setContentView(tv);

        SharedPreferences sp = getSharedPreferences("error_logs", MODE_PRIVATE);
        String logs = sp.getString("logs", "暂无日志");
        tv.setText(logs);
    }
} 