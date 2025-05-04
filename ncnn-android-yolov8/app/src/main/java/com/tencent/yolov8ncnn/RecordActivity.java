package com.tencent.yolov8ncnn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.GridView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RecordActivity extends Activity {
    private GridView gridCards;
    private TextView tvCount;
    private Button btnClear;
    private CardAdapter cardAdapter;
    private List<String> cardOrder = new ArrayList<>();
    private static final int TOTAL_CARDS = 52;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record);

        gridCards = (GridView) findViewById(R.id.gridCards);
        tvCount = (TextView) findViewById(R.id.tvCount);
        btnClear = (Button) findViewById(R.id.btnClear);

        loadCardOrder();
        cardAdapter = new CardAdapter(this, cardOrder);
        gridCards.setAdapter(cardAdapter);
        updateCount();

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(RecordActivity.this)
                        .setTitle("确认清空")
                        .setMessage("确定要清空所有记录吗？")
                        .setPositiveButton("确定", (dialog, which) -> {
                            cardOrder.clear();
                            saveCardOrder();
                            cardAdapter.notifyDataSetChanged();
                            updateCount();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
    }

    private void loadCardOrder() {
        SharedPreferences sp = getSharedPreferences("card_records", MODE_PRIVATE);
        String orderStr = sp.getString("card_order", "");
        cardOrder.clear();
        if (!orderStr.isEmpty()) {
            cardOrder.addAll(Arrays.asList(orderStr.split(",")));
        }
    }

    private void saveCardOrder() {
        SharedPreferences sp = getSharedPreferences("card_records", MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cardOrder.size(); i++) {
            sb.append(cardOrder.get(i));
            if (i != cardOrder.size() - 1) sb.append(",");
        }
        sp.edit().putString("card_order", sb.toString()).apply();
    }

    private void updateCount() {
        tvCount.setText(cardOrder.size() + "/" + TOTAL_CARDS);
    }
} 