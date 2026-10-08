package com.modar.samp;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

/**
 * Проверка ядра лаунчера (SampQuery) без Android: гоняем опрос по-настоящему по UDP
 * против mock-сервера SA-MP (samp-launcher/tools/mock-samp-server.js).
 *
 *   java com.modar.samp.SampQueryTest <портUtf8> <портCp1251> <пустойПорт>
 */
public final class SampQueryTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int portUtf8 = args.length > 0 ? Integer.parseInt(args[0]) : 7831;
        int portCp1251 = args.length > 1 ? Integer.parseInt(args[1]) : 7832;
        // ожидаемое имя задаём в коде: аргументы командной строки JVM может перекодировать
        String cyrillicName = "Русский Мод | Сервер";
        int deadPort = args.length > 2 ? Integer.parseInt(args[2]) : 7899;

        System.out.println("== Опрос UTF-8 сервера (" + host + ":" + portUtf8 + ")");
        SampQuery.Info info = SampQuery.info(host, portUtf8, 1500);
        check("название сервера", "Modar Test Server | Mobile", info.hostname);
        check("режим", "Modar RolePlay", info.gamemode);
        check("язык", "Russian", info.language);
        check("игроков", 3, info.players);
        check("максимум игроков", 100, info.maxPlayers);
        check("без пароля", false, info.password);

        int ping = SampQuery.ping(host, portUtf8, 1500);
        checkTrue("задержка измеряется", ping >= 0 && ping < 1000, ping + " мс");

        System.out.println("== Правила сервера");
        Map<String, String> rules = SampQuery.rules(host, portUtf8, 1500);
        check("правил не меньше 7", true, rules.size() >= 7);
        check("карта", "San Andreas", rules.get("mapname"));
        check("версия", "0.3.7-R2", rules.get("version"));
        check("компенсация лага", "On", rules.get("lagcomp"));

        System.out.println("== Игроки (подробный список, код 'd')");
        List<SampQuery.Player> players = SampQuery.players(host, portUtf8, 1500);
        check("игроков в списке", 3, players.size());
        check("первый игрок", "ModarAdmin", players.get(0).name);
        check("счёт первого", 100, players.get(0).score);
        check("пинг первого", 20, players.get(0).ping);
        check("id первого", 0, players.get(0).id);
        check("третий игрок", "Игрок3", players.get(2).name);

        System.out.println("== Игроки (краткий список, код 'c')");
        List<SampQuery.Player> basic = SampQuery.playersBasic(host, portUtf8, 1500);
        check("игроков в кратком списке", 3, basic.size());
        check("счёт во втором списке", 93, basic.get(1).score);
        check("пинга в кратком списке нет", -1, basic.get(1).ping);

        System.out.println("== Русское название в кодировке Windows-1251 (" + host + ":" + portCp1251 + ")");
        SampQuery.Info russian = SampQuery.info(host, portCp1251, 1500);
        check("CP1251 раскодирован", cyrillicName, russian.hostname);

        System.out.println("== Сервер молчит (" + host + ":" + deadPort + ")");
        long started = System.currentTimeMillis();
        boolean thrown = false;
        try {
            SampQuery.info(host, deadPort, 800);
        } catch (SocketTimeoutException e) {
            thrown = true;
        } catch (java.io.IOException e) {
            thrown = true; // на localhost возможен ICMP «порт закрыт» — тоже корректный отказ
        }
        long spent = System.currentTimeMillis() - started;
        checkTrue("оффлайн определяется как ошибка", thrown, "исключение за " + spent + " мс");
        checkTrue("ждём не дольше таймаута", spent < 4000, spent + " мс");

        System.out.println("== Проверка контрольной суммы (Hash)");
        java.io.File probe = java.io.File.createTempFile("modar-hash", ".txt");
        java.io.FileOutputStream out = new java.io.FileOutputStream(probe);
        out.write("modar".getBytes("UTF-8"));
        out.close();
        // эталон посчитан снаружи: printf 'modar' | sha256sum
        String expected = "25ef43e0a55469a4d305c86e630cd4a03d2bfe71200d4e54e2be0d17998b9bdc";
        String hash = Hash.sha256(probe);
        check("sha256 файла", expected, hash);
        checkTrue("совпадение по ожидаемому хешу (верхний регистр)", Hash.matches(probe, expected.toUpperCase()), expected);
        checkTrue("подмена файла ловится", !Hash.matches(probe, "00" + expected.substring(2)), "хеш не совпадёт");
        probe.delete();

        System.out.println();
        System.out.println("Итог: пройдено " + passed + ", провалено " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String title, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
            System.out.println("  ✓ " + title + ": " + actual);
        } else {
            failed++;
            System.out.println("  ✗ " + title + ": ожидалось «" + expected + "», получено «" + actual + "»");
        }
    }

    private static void checkTrue(String title, boolean condition, String note) {
        if (condition) {
            passed++;
            System.out.println("  ✓ " + title + " (" + note + ")");
        } else {
            failed++;
            System.out.println("  ✗ " + title + " (" + note + ")");
        }
    }
}
