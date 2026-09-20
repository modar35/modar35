package com.modar.ai;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.graphics.Typeface;

/**
 * Очень лёгкий Markdown-рендерер: заголовки, списки, **жирный**, *курсив*,
 * `код`, ```блоки кода```, [ссылки](url) и > цитаты.
 * Никаких зависимостей — только Spannable.
 */
public class Markdown {

    public static CharSequence render(String src, int codeBg, int linkColor) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (src == null || src.isEmpty()) {
            return out;
        }
        String[] lines = src.split("\n", -1);
        boolean inCode = false;
        StringBuilder code = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                if (inCode) {
                    appendCodeBlock(out, code.toString(), codeBg);
                    code.setLength(0);
                    inCode = false;
                } else {
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                if (code.length() > 0) {
                    code.append('\n');
                }
                code.append(line);
                continue;
            }

            if (trimmed.isEmpty()) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                    out.append("\n");
                }
                continue;
            }

            // заголовки
            int level = 0;
            while (level < 6 && level < line.length() && line.charAt(level) == '#') {
                level++;
            }
            if (level > 0 && line.length() > level && line.charAt(level) == ' ') {
                String text = line.substring(level + 1).trim();
                int start = out.length();
                appendInline(out, text, codeBg, linkColor);
                int end = out.length();
                if (end > start) {
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(level == 1 ? 1.22f : 1.12f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                out.append("\n\n");
                continue;
            }

            // цитата
            if (trimmed.startsWith("> ")) {
                int start = out.length();
                out.append("▏");
                appendInline(out, line.substring(line.indexOf('>') + 1).trim(), codeBg, linkColor);
                int end = out.length();
                if (end > start) {
                    out.setSpan(new ForegroundColorSpan(linkColor), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                out.append("\n");
                continue;
            }

            // маркированные списки
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                String indent = line.substring(0, line.indexOf(trimmed.charAt(0)));
                out.append(indent).append("•  ");
                appendInline(out, trimmed.substring(2), codeBg, linkColor);
                out.append("\n");
                continue;
            }

            appendInline(out, line, codeBg, linkColor);
            out.append("\n");
        }

        if (inCode && code.length() > 0) {
            appendCodeBlock(out, code.toString(), codeBg);
        }
        // убрать последний лишний перевод строки
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
        return out;
    }

    private static void appendCodeBlock(SpannableStringBuilder out, String code, int codeBg) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append("\n");
        }
        int start = out.length();
        out.append(code);
        int end = out.length();
        if (end > start) {
            out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new BackgroundColorSpan(codeBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new RelativeSizeSpan(0.92f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        out.append("\n\n");
    }

    private static void appendInline(SpannableStringBuilder out, String text, int codeBg, int linkColor) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    int s = out.length();
                    out.append(text, i + 1, end);
                    int e = out.length();
                    out.setSpan(new TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(codeBg), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '*') {
                boolean bold = i + 1 < n && text.charAt(i + 1) == '*';
                String marker = bold ? "**" : "*";
                int end = text.indexOf(marker, i + marker.length());
                if (end > i) {
                    int s = out.length();
                    appendInline(out, text.substring(i + marker.length(), end), codeBg, linkColor);
                    int e = out.length();
                    if (e > s) {
                        out.setSpan(new StyleSpan(bold ? Typeface.BOLD : Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    i = end + marker.length();
                    continue;
                }
            }
            if (c == '[') {
                int close = text.indexOf(']', i);
                if (close > i && close + 1 < n && text.charAt(close + 1) == '(') {
                    int paren = text.indexOf(')', close + 1);
                    if (paren > close) {
                        int s = out.length();
                        out.append(text, i + 1, close);
                        int e = out.length();
                        if (e > s) {
                            out.setSpan(new ForegroundColorSpan(linkColor), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            out.setSpan(new UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        i = paren + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
    }

    /** Текст без разметки — для озвучки. */
    public static String toPlainText(String md) {
        if (md == null) {
            return "";
        }
        String s = md.replaceAll("(?s)```.*?```", " блок кода пропущен. ");
        s = s.replaceAll("`([^`]*)`", "$1");
        s = s.replaceAll("\\*\\*([^*]*)\\*\\*", "$1");
        s = s.replaceAll("(?m)^#{1,6}\\s*", "");
        s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        s = s.replaceAll("\\*", "");
        return s.trim();
    }
}
