package com.modar.samp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Опрос SA-MP-серверов по протоколу Query (UDP) — без сторонних библиотек и без Android-API.
 *
 * Формат пакета: "SAMP" + 4 байта IP + порт (2 байта, little-endian) + код запроса.
 * Коды: 'i' — основная информация, 'r' — правила, 'c' — список игроков,
 * 'd' — список игроков с пингом, 'p' — измерение задержки.
 *
 * Ответ любого запроса начинается теми же 11 байтами (заголовок), данные идут с 11-го байта.
 *
 * Класс специально не зависит от Android: ту же логику проверяет
 * samp-launcher/tools/SampQueryTest.java на настоящем UDP-сервере.
 */
public final class SampQuery {

    public static final char OP_INFO = 'i';
    public static final char OP_RULES = 'r';
    public static final char OP_CLIENTS = 'c';
    public static final char OP_DETAILED = 'd';
    public static final char OP_PING = 'p';

    public static final int DEFAULT_PORT = 7777;
    public static final int DEFAULT_TIMEOUT = 1500;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Короткая информация о сервере (ответ на 'i'). */
    public static final class Info {
        public String host = "";
        public int port = DEFAULT_PORT;
        public String hostname = "";
        public String gamemode = "";
        public String language = "";
        public int players;
        public int maxPlayers;
        public boolean password;
        /** Задержка ответа сервера, мс. */
        public int rtt;
        /** Когда получен ответ (мс, System.currentTimeMillis). */
        public long time = System.currentTimeMillis();

        public boolean isFree() {
            return maxPlayers > 0 && players < maxPlayers;
        }

        public String fill() {
            return players + "/" + maxPlayers;
        }
    }

    /** Игрок из ответов 'c' (без пинга) и 'd' (с пингом). */
    public static final class Player {
        public int id = -1;
        public String name = "";
        public int score;
        public int ping = -1;

        public String label() {
            return id >= 0 ? "[" + id + "] " + name : name;
        }
    }

    private SampQuery() {
    }

    /* ------------------------------------------------------------------ запросы */

    public static Info info(String host, int port, int timeoutMs) throws IOException {
        byte[] reply = request(host, port, OP_INFO, timeoutMs);
        if (reply.length < 16) {
            throw new IOException("Короткий ответ сервера (" + reply.length + " байт)");
        }
        Info info = new Info();
        info.host = host;
        info.port = port;
        info.password = reply[11] != 0;
        info.players = u16(reply, 12);
        info.maxPlayers = u16(reply, 14);

        int pos = 16;
        Str hostname = str(reply, pos);
        pos = hostname.next;
        Str gamemode = str(reply, pos);
        pos = gamemode.next;
        Str language = str(reply, pos);

        info.hostname = hostname.value;
        info.gamemode = gamemode.value;
        info.language = language.value;
        return info;
    }

    /** Правила сервера (ответ на 'r'): mapname, version, weather и прочее. */
    public static Map<String, String> rules(String host, int port, int timeoutMs) throws IOException {
        byte[] reply = request(host, port, OP_RULES, timeoutMs);
        Map<String, String> rules = new LinkedHashMap<String, String>();
        if (reply.length < 13) {
            return rules;
        }
        int count = u16(reply, 11);
        int pos = 13;
        for (int i = 0; i < count && pos < reply.length; i++) {
            if (pos >= reply.length) {
                break;
            }
            int nameLen = reply[pos] & 0xFF;
            pos++;
            if (pos + nameLen > reply.length) {
                break;
            }
            String name = decode(reply, pos, nameLen);
            pos += nameLen;
            if (pos >= reply.length) {
                break;
            }
            int valueLen = reply[pos] & 0xFF;
            pos++;
            if (pos + valueLen > reply.length) {
                break;
            }
            String value = decode(reply, pos, valueLen);
            pos += valueLen;
            rules.put(name, value);
        }
        return rules;
    }

    /** Список игроков: 'd' отдаёт ещё и пинг, 'c' — только счёт (некоторые серверы его ограничивают). */
    public static List<Player> players(String host, int port, int timeoutMs) throws IOException {
        return parsePlayers(request(host, port, OP_DETAILED, timeoutMs), true);
    }

    /** Список игроков без пинга (совместимый вариант). */
    public static List<Player> playersBasic(String host, int port, int timeoutMs) throws IOException {
        return parsePlayers(request(host, port, OP_CLIENTS, timeoutMs), false);
    }

    /** Задержка по коду 'p': сервер возвращает четыре отправленных случайных байта. */
    public static int ping(String host, int port, int timeoutMs) throws IOException {
        long started = System.currentTimeMillis();
        byte[] query = packet(host, port, OP_PING);
        byte[] random = new byte[4];
        new java.util.Random().nextBytes(random);
        byte[] full = new byte[query.length + random.length];
        System.arraycopy(query, 0, full, 0, query.length);
        System.arraycopy(random, 0, full, query.length, random.length);

        byte[] reply = exchange(host, port, full, OP_PING, timeoutMs);
        int rtt = (int) (System.currentTimeMillis() - started);
        if (reply.length < 15) {
            throw new IOException("Сервер не ответил на запрос задержки");
        }
        for (int i = 0; i < 4; i++) {
            if (reply[11 + i] != random[i]) {
                throw new IOException("Ответ на запрос задержки не совпал с запросом");
            }
        }
        return rtt;
    }

    /**
     * Отправляет запрос и ждёт ответ. Пробует до двух раз в пределах общего таймаута:
     * UDP не гарантирует доставку, а потеря одного пакета — обычное дело.
     */
    public static byte[] request(String host, int port, char opcode, int timeoutMs) throws IOException {
        return exchange(host, port, packet(host, port, opcode), opcode, timeoutMs);
    }

    /** Собирает пакет запроса: "SAMP" + 4 байта IP + порт + код. */
    public static byte[] packet(String host, int port, char opcode) throws IOException {
        if (host == null || host.trim().isEmpty()) {
            throw new IOException("Не указан адрес сервера");
        }
        if (port <= 0 || port > 65535) {
            throw new IOException("Неверный порт: " + port);
        }
        byte[] ip = ipv4(InetAddress.getByName(host.trim()));
        byte[] query = new byte[11];
        query[0] = 'S';
        query[1] = 'A';
        query[2] = 'M';
        query[3] = 'P';
        System.arraycopy(ip, 0, query, 4, 4);
        query[8] = (byte) (port & 0xFF);
        query[9] = (byte) ((port >> 8) & 0xFF);
        query[10] = (byte) opcode;
        return query;
    }

    private static byte[] exchange(String host, int port, byte[] query, char opcode, int timeoutMs) throws IOException {
        if (host == null || host.trim().isEmpty()) {
            throw new IOException("Не указан адрес сервера");
        }
        int budget = timeoutMs <= 0 ? DEFAULT_TIMEOUT : timeoutMs;
        InetAddress address = InetAddress.getByName(host.trim());

        DatagramSocket socket = new DatagramSocket();
        try {
            socket.connect(address, port);
            long deadline = System.currentTimeMillis() + budget;
            int attempts = 0;
            while (true) {
                socket.send(new DatagramPacket(query, query.length));
                attempts++;
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                socket.setSoTimeout((int) Math.max(200, left));
                byte[] buffer = new byte[8192];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(response);
                } catch (SocketTimeoutException timeout) {
                    if (attempts >= 2) {
                        break;
                    }
                    continue;
                }
                byte[] data = Arrays.copyOf(response.getData(), response.getLength());
                if (data.length < 11 || data[0] != 'S' || data[1] != 'A' || data[2] != 'M' || data[3] != 'P') {
                    continue; // чужой пакет — ждём дальше
                }
                if (data[10] != (byte) opcode) {
                    continue; // ответ на другой запрос
                }
                return data;
            }
        } finally {
            socket.close();
        }
        throw new SocketTimeoutException("Сервер " + host + ":" + port + " не ответил за " + budget + " мс");
    }

    /* ------------------------------------------------------------------ разбор */

    private static List<Player> parsePlayers(byte[] reply, boolean withPing) {
        List<Player> players = new ArrayList<Player>();
        if (reply.length < 13) {
            return players;
        }
        int count = u16(reply, 11);
        int pos = 13;
        for (int i = 0; i < count; i++) {
            Player player = new Player();
            if (withPing) {
                if (pos >= reply.length) {
                    break;
                }
                player.id = reply[pos] & 0xFF;
                pos++;
            }
            if (pos >= reply.length) {
                break;
            }
            int nameLen = reply[pos] & 0xFF;
            pos++;
            if (pos + nameLen + 4 > reply.length) {
                break;
            }
            player.name = decode(reply, pos, nameLen);
            pos += nameLen;
            player.score = u32(reply, pos);
            pos += 4;
            if (withPing) {
                if (pos + 4 > reply.length) {
                    break;
                }
                player.ping = u32(reply, pos);
                pos += 4;
            }
            players.add(player);
        }
        return players;
    }

    private static final class Str {
        final String value;
        final int next;

        Str(String value, int next) {
            this.value = value;
            this.next = next;
        }
    }

    /** Строка с 4-байтовой длиной (little-endian) впереди. */
    private static Str str(byte[] data, int pos) {
        if (pos + 4 > data.length) {
            return new Str("", data.length);
        }
        int length = u32(data, pos);
        int start = pos + 4;
        if (length < 0 || start + length > data.length) {
            length = Math.max(0, data.length - start);
        }
        return new Str(decode(data, start, length), start + length);
    }

    private static int u16(byte[] data, int pos) {
        if (pos + 2 > data.length) {
            return 0;
        }
        return (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] data, int pos) {
        if (pos + 4 > data.length) {
            return 0;
        }
        return (data[pos] & 0xFF)
                | ((data[pos + 1] & 0xFF) << 8)
                | ((data[pos + 2] & 0xFF) << 16)
                | ((data[pos + 3] & 0xFF) << 24);
    }

    private static byte[] ipv4(InetAddress address) throws IOException {
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            return raw;
        }
        byte[] mapped = null;
        if (raw.length == 16) {
            boolean ours = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) {
                    ours = false;
                    break;
                }
            }
            if (ours && raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF) {
                mapped = Arrays.copyOfRange(raw, 12, 16);
            }
        }
        if (mapped == null) {
            throw new IOException("Протокол SA-MP работает только по IPv4, а «" + address.getHostAddress() + "» — IPv6");
        }
        return mapped;
    }

    /* ------------------------------------------------------------------ строки */

    /**
     * SA-MP не сообщает кодировку названий. Серверы с русскими названиями обычно отдают
     * Windows-1251, остальные — UTF-8. Пробуем UTF-8, а если байты в неё не укладываются,
     * раскодируем таблицей CP1251: на Android и на JVM наборы кодировок различаются,
     * поэтому таблица встроена в код.
     */
    public static String decode(byte[] data, int offset, int length) {
        if (length <= 0) {
            return "";
        }
        String raw = new String(data, offset, length, UTF8);
        if (raw.indexOf('\uFFFD') < 0) {
            return raw;
        }
        return cp1251(data, offset, length);
    }

    private static final int[] CP1251_80 = {
            0x0402, 0x0403, 0x201A, 0x0453, 0x201E, 0x2026, 0x2020, 0x2021,
            0x20AC, 0x2030, 0x0409, 0x2039, 0x040A, 0x040C, 0x040B, 0x040F,
            0x0452, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
            0x0098, 0x2122, 0x0459, 0x203A, 0x045A, 0x045C, 0x045B, 0x045F,
            0x00A0, 0x040E, 0x045E, 0x0408, 0x00A4, 0x0490, 0x00A6, 0x00A7,
            0x0401, 0x00A9, 0x0404, 0x00AB, 0x00AC, 0x00AD, 0x00AE, 0x0407,
            0x00B0, 0x00B1, 0x0406, 0x0456, 0x0491, 0x00B5, 0x00B6, 0x00B7,
            0x0451, 0x2116, 0x0454, 0x00BB, 0x0458, 0x0405, 0x0455, 0x0457,
    };

    private static String cp1251(byte[] data, int offset, int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int b = data[offset + i] & 0xFF;
            if (b < 0x80) {
                out.append((char) b);
            } else if (b < 0xC0) {
                out.append((char) CP1251_80[b - 0x80]);
            } else {
                out.append((char) (0x0410 + (b - 0xC0)));
            }
        }
        return out.toString();
    }
}
