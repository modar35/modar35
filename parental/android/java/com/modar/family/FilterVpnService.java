package com.modar.family;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Фильтр интернета на телефоне ребёнка.
 *
 * Служба поднимает локальный VPN, который пропускает через себя только DNS-запросы
 * (остальной трафик идёт напрямую, скорость не страдает). Каждый запрос проверяется
 * по правилам родителя:
 *
 *   • «взрослый контент», азартные игры и свои запрещённые сайты — NXDOMAIN;
 *   • режим «интернет по расписанию» — все домены закрыты, интернет пропадает
 *     у всех приложений, при этом звонки и SMS работают.
 *
 * Это стандартный способ фильтрации без root-прав. Службу включает родитель
 * кнопкой «Включить фильтр сайтов» на телефоне ребёнка (Android спросит согласие).
 */
public class FilterVpnService extends VpnService {

    public static final String ACTION_START = "com.modar.family.VPN_START";
    public static final String ACTION_STOP = "com.modar.family.VPN_STOP";
    private static final String CHANNEL = "modar_vpn";
    private static final int NOTIF_ID = 1004;
    private static final String TUN_ADDRESS = "10.111.222.2";
    private static final String TUN_DNS = "10.111.222.1";
    private static final String UPSTREAM = "1.1.1.1";

    private static volatile boolean running;
    private static volatile boolean internetOff;
    private static Thread worker;
    private static ParcelFileDescriptor tunnel;
    private static final List<JSONObject> BLOCKED = new ArrayList<JSONObject>();

    public static boolean isRunning() {
        return running;
    }

    public static void setInternetOff(Context ctx, boolean off) {
        if (internetOff != off) {
            internetOff = off;
            if (off) {
                push(ctx, "Интернет выключен по расписанию", "Все сайты закрыты до окончания режима");
            }
        }
    }

    public static void start(Context ctx) {
        Intent intent = new Intent(ctx, FilterVpnService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    public static void stop(Context ctx) {
        Intent intent = new Intent(ctx, FilterVpnService.class);
        intent.setAction(ACTION_STOP);
        try {
            ctx.startService(intent);
        } catch (Exception ignored) {
        }
    }

    /** Событие о заблокированном домене (уйдёт родителю со следующим отчётом). */
    public static void report(String host, String reason) {
        JSONObject item = new JSONObject();
        try {
            item.put("host", host);
            item.put("reason", reason);
        } catch (Exception e) {
            return;
        }
        synchronized (FilterVpnService.class) {
            BLOCKED.add(item);
            while (BLOCKED.size() > 40) {
                BLOCKED.remove(0);
            }
        }
    }

    public static synchronized JSONArray drainBlocked() {
        JSONArray array = new JSONArray();
        for (JSONObject item : BLOCKED) {
            array.put(item);
        }
        BLOCKED.clear();
        return array;
    }

    private static void push(Context ctx, String title, String text) {
        try {
            NotificationManager manager = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel("modar_events") == null) {
                NotificationChannel channel = new NotificationChannel("modar_events", "События",
                        NotificationManager.IMPORTANCE_DEFAULT);
                manager.createNotificationChannel(channel);
            }
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, "modar_events")
                    : new Notification.Builder(ctx);
            manager.notify((int) System.currentTimeMillis(), builder
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setAutoCancel(true)
                    .build());
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) {
            return START_STICKY;
        }
        try {
            startForegroundNotification();
            Builder builder = new Builder();
            builder.setSession("Modar Family");
            builder.addAddress(TUN_ADDRESS, 32);
            builder.addDnsServer(TUN_DNS);
            builder.addRoute(TUN_DNS, 32);
            builder.setBlocking(true);
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Exception ignored) {
            }
            builder.setConfigureIntent(PendingIntent.getActivity(this, 0, new Intent(this, ChildActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            if (Build.VERSION.SDK_INT >= 29) {
                builder.setMetered(false);
            }
            tunnel = builder.establish();
            if (tunnel == null) {
                Prefs.setFlag(this, "vpn", false);
                stopSelf();
                return START_NOT_STICKY;
            }
            running = true;
            Prefs.setFlag(this, "vpn", true);
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "modar-dns");
            worker.start();
        } catch (Exception e) {
            Prefs.put(this, "vpn_error", String.valueOf(e.getMessage()));
            running = false;
            stopSelf();
        }
        return START_STICKY;
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Фильтр интернета",
                    NotificationManager.IMPORTANCE_MIN);
            manager.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Modar Family")
                .setContentText("Фильтр сайтов включён")
                .setSmallIcon(R.drawable.ic_shield)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notification);
    }

    /* ------------------------------------------------------------- основной цикл */

    private void loop() {
        byte[] buffer = new byte[32767];
        FileInputStream in = new FileInputStream(tunnel.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(tunnel.getFileDescriptor());
        try {
            while (running) {
                int length = in.read(buffer);
                if (length <= 0) {
                    continue;
                }
                byte[] packet = new byte[length];
                System.arraycopy(buffer, 0, packet, 0, length);
                try {
                    handlePacket(packet, out);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Prefs.put(this, "vpn_error", String.valueOf(e.getMessage()));
        } finally {
            try {
                in.close();
                out.close();
            } catch (Exception ignored) {
            }
            running = false;
        }
    }

    private void handlePacket(byte[] packet, FileOutputStream out) throws Exception {
        if (packet.length < 28 || (packet[0] >> 4) != 4) {
            return;
        }
        int ihl = (packet[0] & 0x0F) * 4;
        if (ihl < 20 || packet.length < ihl + 8) {
            return;
        }
        int protocol = packet[9] & 0xFF;
        if (protocol != 17) {
            return; // только UDP (DNS)
        }
        int udpOffset = ihl;
        int srcPort = ((packet[udpOffset] & 0xFF) << 8) | (packet[udpOffset + 1] & 0xFF);
        int dstPort = ((packet[udpOffset + 2] & 0xFF) << 8) | (packet[udpOffset + 3] & 0xFF);
        int udpLength = ((packet[udpOffset + 4] & 0xFF) << 8) | (packet[udpOffset + 5] & 0xFF);
        if (udpLength < 12 || dstPort != 53) {
            return;
        }
        int dnsOffset = udpOffset + 8;
        int dnsLength = udpLength - 8;
        if (packet.length < dnsOffset + dnsLength) {
            return;
        }
        byte[] dns = new byte[dnsLength];
        System.arraycopy(packet, dnsOffset, dns, 0, dnsLength);

        String domain = questionName(dns);
        if (domain == null) {
            return;
        }
        int qtype = questionType(dns);

        String reason = null;
        if (internetOff) {
            reason = "schedule";
        } else if (WebFilterService.isBlocked(domain)) {
            reason = "filter";
        }
        if (reason != null) {
            report(domain, reason);
            byte[] blocked = blockResponse(dns);
            writeResponse(packet, ihl, srcPort, blocked, out);
            return;
        }

        byte[] answer = forward(dns);
        if (answer != null) {
            writeResponse(packet, ihl, srcPort, answer, out);
        }
    }

    /** Отправляет запрос на внешний DNS через защищённый сокет. */
    private byte[] forward(byte[] query) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            protect(socket);
            socket.setSoTimeout(4000);
            InetAddress upstream = InetAddress.getByName(UPSTREAM);
            socket.send(new DatagramPacket(query, query.length, upstream, 53));
            byte[] buffer = new byte[4096];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            byte[] result = new byte[response.getLength()];
            System.arraycopy(response.getData(), 0, result, 0, response.getLength());
            return result;
        } catch (Exception e) {
            return null;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    /** NXDOMAIN: так сайт не открывается, а приложение показывает «нет соединения». */
    private static byte[] blockResponse(byte[] query) {
        byte[] response = new byte[query.length];
        System.arraycopy(query, 0, response, 0, query.length);
        response[2] = (byte) 0x81; // QR=1, RD=1
        response[3] = (byte) 0x83; // RA=1, RCODE=3 (NXDOMAIN)
        response[6] = 0;
        response[7] = 0; // ответов нет
        response[8] = 0;
        response[9] = 0;
        response[10] = 0;
        response[11] = 0;
        return response;
    }

    /** Собирает IPv4+UDP пакет с ответом и отправляет его в туннель. */
    private static void writeResponse(byte[] request, int ihl, int srcPort, byte[] payload, FileOutputStream out)
            throws Exception {
        int total = 20 + 8 + payload.length;
        byte[] packet = new byte[total];
        // IPv4
        packet[0] = 0x45;
        packet[1] = 0;
        packet[2] = (byte) (total >> 8);
        packet[3] = (byte) (total & 0xFF);
        packet[4] = 0;
        packet[5] = 0;
        packet[6] = 0x40; // DF
        packet[7] = 0;
        packet[8] = 64;   // TTL
        packet[9] = 17;   // UDP
        packet[10] = 0;
        packet[11] = 0;
        // Меняем местами адреса: ответ идёт с адреса DNS телефона на адрес приложения
        System.arraycopy(request, 16, packet, 12, 4);  // src = адрес получателя запроса
        System.arraycopy(request, 12, packet, 16, 4);  // dst = адрес отправителя запроса
        int ipChecksum = checksum(packet, 0, 20);
        packet[10] = (byte) (ipChecksum >> 8);
        packet[11] = (byte) (ipChecksum & 0xFF);
        // UDP
        int udpOffset = 20;
        int dstPort = ((request[ihl] & 0xFF) << 8) | (request[ihl + 1] & 0xFF);
        packet[udpOffset] = (byte) (53 >> 8);
        packet[udpOffset + 1] = 53;
        packet[udpOffset + 2] = (byte) (dstPort >> 8);
        packet[udpOffset + 3] = (byte) (dstPort & 0xFF);
        int udpLength = 8 + payload.length;
        packet[udpOffset + 4] = (byte) (udpLength >> 8);
        packet[udpOffset + 5] = (byte) (udpLength & 0xFF);
        packet[udpOffset + 6] = 0;
        packet[udpOffset + 7] = 0;
        System.arraycopy(payload, 0, packet, udpOffset + 8, payload.length);
        int udpChecksum = udpChecksum(packet, 20, udpLength);
        packet[udpOffset + 6] = (byte) (udpChecksum >> 8);
        packet[udpOffset + 7] = (byte) (udpChecksum & 0xFF);
        out.write(packet);
        out.flush();
    }

    private static int checksum(byte[] data, int offset, int length) {
        long sum = 0;
        for (int i = 0; i < length - 1; i += 2) {
            sum += ((data[offset + i] & 0xFF) << 8) | (data[offset + i + 1] & 0xFF);
        }
        if (length % 2 == 1) {
            sum += (data[offset + length - 1] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum) & 0xFFFF;
    }

    private static int udpChecksum(byte[] packet, int udpOffset, int udpLength) {
        long sum = 0;
        for (int i = 12; i < 20; i += 2) { // псевдозаголовок: адреса
            sum += ((packet[i] & 0xFF) << 8) | (packet[i + 1] & 0xFF);
        }
        sum += 17;                          // протокол
        sum += udpLength;
        for (int i = 0; i < udpLength - 1; i += 2) {
            sum += ((packet[udpOffset + i] & 0xFF) << 8) | (packet[udpOffset + i + 1] & 0xFF);
        }
        if (udpLength % 2 == 1) {
            sum += (packet[udpOffset + udpLength - 1] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        int result = (int) (~sum) & 0xFFFF;
        return result == 0 ? 0xFFFF : result;
    }

    /* ----------------------------------------------------------- разбор DNS */

    /** Имя домена из вопроса DNS-запроса. */
    static String questionName(byte[] dns) {
        if (dns.length < 13) {
            return null;
        }
        int index = 12;
        StringBuilder name = new StringBuilder();
        while (index < dns.length) {
            int length = dns[index] & 0xFF;
            if (length == 0) {
                break;
            }
            if ((length & 0xC0) != 0 || index + length >= dns.length) {
                return null; // сжатие в вопросе не встречается
            }
            String label = new String(dns, index + 1, length, java.nio.charset.Charset.forName("US-ASCII"));
            if (name.length() > 0) {
                name.append('.');
            }
            name.append(label.toLowerCase(Locale.US));
            index += length + 1;
        }
        return name.length() == 0 ? null : name.toString();
    }

    static int questionType(byte[] dns) {
        int index = 12;
        while (index < dns.length) {
            int length = dns[index] & 0xFF;
            if (length == 0) {
                index += 1;
                break;
            }
            if ((length & 0xC0) != 0) {
                return 1;
            }
            index += length + 1;
        }
        if (index + 1 >= dns.length) {
            return 1;
        }
        return ((dns[index] & 0xFF) << 8) | (dns[index + 1] & 0xFF);
    }

    /* ------------------------------------------------------------- жизненный цикл */

    @Override
    public void onRevoke() {
        running = false;
        Prefs.setFlag(this, "vpn", false);
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        try {
            if (tunnel != null) {
                tunnel.close();
                tunnel = null;
            }
        } catch (Exception ignored) {
        }
        Prefs.setFlag(this, "vpn", false);
        super.onDestroy();
    }
}
