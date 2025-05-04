package com.tencent.yolov8ncnn;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

public class CardAdapter extends BaseAdapter {
    private final Context context;
    private final List<String> cardList;

    public CardAdapter(Context context, List<String> cardList) {
        this.context = context;
        this.cardList = cardList;
    }

    @Override
    public int getCount() {
        return cardList.size();
    }

    @Override
    public Object getItem(int position) {
        return cardList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.card_item, parent, false);
            holder = new ViewHolder();
            holder.tvEmoji = (TextView) convertView.findViewById(R.id.tvEmoji);
            holder.tvCardText = (TextView) convertView.findViewById(R.id.tvCardText);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        String card = cardList.get(position);
        // 解析花色和点数
        String emoji = getEmoji(card);
        String text = getCardText(card);
        holder.tvEmoji.setText(emoji);
        holder.tvCardText.setText(text);
        return convertView;
    }

    private String getEmoji(String card) {
        if (card.contains("梅花")) return "♣";
        if (card.contains("方块")) return "♦";
        if (card.contains("红桃")) return "♥";
        if (card.contains("黑桃")) return "♠";
        return "?";
    }

    private String getCardText(String card) {
        // 去掉花色，保留点数
        return card.replace("梅花","").replace("方块","").replace("红桃","").replace("黑桃","");
    }

    static class ViewHolder {
        TextView tvEmoji;
        TextView tvCardText;
    }
} 