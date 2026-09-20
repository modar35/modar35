package com.modar.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Список диалогов: открыть, переименовать, очистить, удалить. */
public class ChatsActivity extends Activity {

    private Store store;
    private List<Conversation> items = new ArrayList<Conversation>();
    private BaseAdapter adapter;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chats);
        store = new Store(this);

        ListView list = (ListView) findViewById(R.id.chats);
        empty = (TextView) findViewById(R.id.chats_empty);

        adapter = new BaseAdapter() {
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
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(ChatsActivity.this)
                            .inflate(R.layout.item_conversation, parent, false);
                }
                Conversation c = items.get(position);
                TextView title = (TextView) convertView.findViewById(R.id.conv_title);
                TextView preview = (TextView) convertView.findViewById(R.id.conv_preview);
                TextView meta = (TextView) convertView.findViewById(R.id.conv_meta);
                title.setText(c.title == null || c.title.trim().isEmpty() ? "Без названия" : c.title);
                preview.setText(c.preview());
                meta.setText(Ui.date(c.updatedAt) + " · " + c.messages.size() + " сообщ.");
                boolean current = c.id.equals(store.currentId());
                convertView.setBackgroundColor(current
                        ? Ui.color(ChatsActivity.this, R.color.surface_alt)
                        : Ui.color(ChatsActivity.this, R.color.surface));
                return convertView;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                store.setCurrentId(items.get(position).id);
                setResult(RESULT_OK);
                finish();
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showActions(items.get(position));
                return true;
            }
        });

        findViewById(R.id.btn_chats_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_chats_new).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.create();
                setResult(RESULT_OK);
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        items = store.all();
        adapter.notifyDataSetChanged();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showActions(final Conversation c) {
        final String[] actions = new String[]{"Открыть", "Переименовать", "Очистить историю", "Удалить"};
        new AlertDialog.Builder(this)
                .setTitle(c.title)
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            store.setCurrentId(c.id);
                            setResult(RESULT_OK);
                            finish();
                        } else if (which == 1) {
                            rename(c);
                        } else if (which == 2) {
                            c.messages.clear();
                            c.title = "Новый диалог";
                            c.updatedAt = System.currentTimeMillis();
                            store.save(c);
                            reload();
                            toast("История очищена");
                        } else {
                            confirmDelete(c);
                        }
                    }
                })
                .show();
    }

    private void rename(final Conversation c) {
        final EditText field = new EditText(this);
        field.setText(c.title);
        field.setSelection(field.getText().length());
        field.setTextColor(Ui.color(this, R.color.text));
        int pad = Ui.dp(this, 16);
        field.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle("Название диалога")
                .setView(field)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = field.getText().toString().trim();
                        c.title = name.isEmpty() ? "Без названия" : name;
                        store.save(c);
                        reload();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void confirmDelete(final Conversation c) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить диалог?")
                .setMessage("\"" + c.title + "\" будет удалён без возможности восстановления.")
                .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        store.delete(c.id);
                        if (c.id.equals(store.currentId())) {
                            store.setCurrentId(null);
                        }
                        reload();
                        setResult(RESULT_OK);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
