package com.modar.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Список сообщений: пузыри пользователя справа, ответы ассистента слева. */
public class ChatAdapter extends BaseAdapter {

    private static final int TYPE_USER = 0;
    private static final int TYPE_AI = 1;

    private final Context ctx;
    private final List<Message> items;
    private final LayoutInflater inflater;

    private final int colorText;
    private final int colorMuted;
    private final int colorError;
    private final int codeBg;
    private final int linkColor;

    public ChatAdapter(Context ctx, List<Message> items) {
        this.ctx = ctx;
        this.items = items;
        this.inflater = LayoutInflater.from(ctx);
        this.colorText = Ui.color(ctx, R.color.text);
        this.colorMuted = Ui.color(ctx, R.color.text_muted);
        this.colorError = Ui.color(ctx, R.color.danger);
        this.codeBg = Ui.color(ctx, R.color.code_bg);
        this.linkColor = Ui.color(ctx, R.color.accent);
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).isUser() ? TYPE_USER : TYPE_AI;
    }

    private static class Holder {
        LinearLayout row;
        LinearLayout bubble;
        TextView text;
        TextView meta;
        ImageView thumb;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        int type = getItemViewType(position);
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_message, parent, false);
            h = new Holder();
            h.row = (LinearLayout) convertView.findViewById(R.id.msg_row);
            h.bubble = (LinearLayout) convertView.findViewById(R.id.msg_bubble);
            h.text = (TextView) convertView.findViewById(R.id.msg_text);
            h.meta = (TextView) convertView.findViewById(R.id.msg_meta);
            h.thumb = (ImageView) convertView.findViewById(R.id.msg_thumb);
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }

        Message m = items.get(position);
        h.row.setGravity(m.isUser() ? Gravity.END : Gravity.START);

        h.bubble.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
        ViewGroup.LayoutParams lp = h.bubble.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).gravity = m.isUser() ? Gravity.END : Gravity.START;
            ((LinearLayout.LayoutParams) lp).setMarginStart(m.isUser() ? Ui.dp(ctx, 44) : 0);
            ((LinearLayout.LayoutParams) lp).setMarginEnd(m.isUser() ? 0 : Ui.dp(ctx, 44));
        }

        if (m.error) {
            h.bubble.setBackgroundResource(R.drawable.bubble_error);
            h.text.setTextColor(colorError);
        } else if (m.isUser()) {
            h.bubble.setBackgroundResource(R.drawable.bubble_user);
            h.text.setTextColor(0xFFFFFFFF);
        } else {
            h.bubble.setBackgroundResource(R.drawable.bubble_ai);
            h.text.setTextColor(colorText);
        }

        String body = m.text == null ? "" : m.text;
        if (m.streaming && body.isEmpty()) {
            h.text.setText("Ассистент печатает…");
            h.text.setTextColor(colorMuted);
        } else if (m.error) {
            h.text.setText(body);
        } else if (m.isUser()) {
            h.text.setText(body);
        } else {
            h.text.setText(Markdown.render(body, codeBg, linkColor));
            h.text.setTextColor(colorText);
        }

        if (m.imagePath != null) {
            Bitmap bmp = Ui.thumbnail(ctx, m.imagePath, 512);
            if (bmp != null) {
                h.thumb.setVisibility(View.VISIBLE);
                h.thumb.setImageBitmap(bmp);
                int w = Math.min(bmp.getWidth(), Ui.dp(ctx, 220));
                int height = (int) (bmp.getHeight() * (w / (float) bmp.getWidth()));
                h.thumb.getLayoutParams().width = w;
                h.thumb.getLayoutParams().height = height;
            } else {
                h.thumb.setVisibility(View.GONE);
            }
        } else {
            h.thumb.setVisibility(View.GONE);
            h.thumb.setImageDrawable(null);
        }

        String meta = Ui.time(m.time);
        if (m.streaming) {
            meta = meta + " · печатает…";
        } else if (m.error && !"assistant".equals(m.role)) {
            meta = meta + " · ошибка";
        }
        h.meta.setText(meta);
        h.meta.setTextColor(m.isUser() ? 0xCCFFFFFF : colorMuted);

        return convertView;
    }

    public void refresh() {
        notifyDataSetChanged();
    }
}
