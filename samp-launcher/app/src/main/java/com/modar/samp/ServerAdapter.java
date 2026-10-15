package com.modar.samp;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Строка списка серверов: название, режим, адрес, игроки, пинг, пароль, избранное. */
public class ServerAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<Server> items = new ArrayList<Server>();

    public ServerAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void setItems(List<Server> servers) {
        items.clear();
        if (servers != null) {
            items.addAll(servers);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Server getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = LayoutInflater.from(ctx).inflate(R.layout.item_server, parent, false);
            Holder holder = new Holder();
            holder.title = (TextView) view.findViewById(R.id.server_title);
            holder.subtitle = (TextView) view.findViewById(R.id.server_subtitle);
            holder.address = (TextView) view.findViewById(R.id.server_address);
            holder.players = (TextView) view.findViewById(R.id.server_players);
            holder.status = (TextView) view.findViewById(R.id.server_status);
            holder.bar = (ProgressBar) view.findViewById(R.id.server_bar);
            holder.lock = (ImageView) view.findViewById(R.id.server_lock);
            holder.star = (ImageView) view.findViewById(R.id.server_star);
            holder.dot = (View) view.findViewById(R.id.server_dot);
            view.setTag(holder);
        }
        Holder holder = (Holder) view.getTag();
        Server server = items.get(position);

        holder.title.setText(server.title());
        holder.address.setText(server.address());

        StringBuilder subtitle = new StringBuilder();
        String gamemode = server.gamemodeLabel();
        if (!gamemode.isEmpty()) {
            subtitle.append(gamemode);
        }
        if (server.last != null && server.last.language != null && !server.last.language.isEmpty()) {
            if (subtitle.length() > 0) {
                subtitle.append(" · ");
            }
            subtitle.append(server.last.language);
        }
        if (server.note != null && !server.note.isEmpty()) {
            if (subtitle.length() > 0) {
                subtitle.append(" · ");
            }
            subtitle.append(server.note);
        }
        holder.subtitle.setText(subtitle.length() == 0 ? "нет данных о режиме" : subtitle.toString());

        holder.lock.setVisibility(server.needsPassword() ? View.VISIBLE : View.GONE);
        holder.star.setVisibility(server.favorite ? View.VISIBLE : View.INVISIBLE);

        SampQuery.Info info = server.last;
        if (info != null && !server.offline) {
            holder.players.setText(info.fill());
            holder.players.setTextColor(Ui.color(ctx, R.color.accent));
            holder.bar.setVisibility(View.VISIBLE);
            holder.bar.setMax(Math.max(1, info.maxPlayers));
            holder.bar.setProgress(Math.min(info.players, Math.max(1, info.maxPlayers)));
            String ping = info.rtt > 0 ? info.rtt + " мс" : "";
            holder.status.setText(ping.isEmpty() ? "онлайн" : "онлайн · " + ping);
            holder.status.setTextColor(Ui.color(ctx, R.color.ok));
            holder.dot.setBackgroundResource(R.drawable.dot_online);
        } else if (server.offline) {
            holder.players.setText("оффлайн");
            holder.players.setTextColor(Ui.color(ctx, R.color.text_muted));
            holder.bar.setVisibility(View.GONE);
            holder.status.setText("нет ответа");
            holder.status.setTextColor(Ui.color(ctx, R.color.text_muted));
            holder.dot.setBackgroundResource(R.drawable.dot_offline);
        } else {
            holder.players.setText("—");
            holder.players.setTextColor(Ui.color(ctx, R.color.text_muted));
            holder.bar.setVisibility(View.GONE);
            holder.status.setText("не опрошен");
            holder.status.setTextColor(Ui.color(ctx, R.color.text_muted));
            holder.dot.setBackgroundResource(R.drawable.dot_unknown);
        }
        Drawable dot = holder.dot.getBackground();
        if (dot != null) {
            dot.setAlpha(255);
        }
        return view;
    }

    private static class Holder {
        TextView title;
        TextView subtitle;
        TextView address;
        TextView players;
        TextView status;
        ProgressBar bar;
        ImageView lock;
        ImageView star;
        View dot;
    }
}
