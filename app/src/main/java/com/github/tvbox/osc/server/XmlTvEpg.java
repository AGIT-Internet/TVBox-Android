package com.github.tvbox.osc.server;

import android.content.Context;
import android.text.TextUtils;
import android.util.Xml;

import com.github.tvbox.osc.util.MD5;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

final class XmlTvEpg {
    private static final long CACHE_MS = 6L * 60L * 60L * 1000L;

    static JSONObject load(Context context, String url, String channelName, String day) throws Exception {
        File file = cachedFile(context, url);
        Set<String> channelIds = findChannelIds(file, channelName);
        JSONArray items = readProgrammes(file, channelIds, channelName, day);
        return new JSONObject().put("date", day).put("items", items).put("cachedAt", file.lastModified());
    }

    private static File cachedFile(Context context, String url) throws Exception {
        File file = new File(context.getCacheDir(), "lan-xmltv-" + MD5.string2MD5(url) + ".xml");
        if (file.isFile() && file.length() > 0 && System.currentTimeMillis() - file.lastModified() < CACHE_MS) {
            return file;
        }
        File temporary = new File(file.getAbsolutePath() + ".download");
        try (okhttp3.Response response = com.github.catvod.net.OkHttp.newCall(url).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("XMLTV 下载失败：" + response.code());
            }
            InputStream input = response.body().byteStream();
            if (url.toLowerCase(Locale.US).endsWith(".gz")) input = new GZIPInputStream(input);
            try (InputStream source = input; FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        }
        if (file.exists() && !file.delete()) throw new IllegalStateException("无法更新 XMLTV 缓存");
        if (!temporary.renameTo(file)) throw new IllegalStateException("无法保存 XMLTV 缓存");
        return file;
    }

    private static Set<String> findChannelIds(File file, String target) throws Exception {
        Set<String> exact = new HashSet<>();
        Set<String> fuzzy = new HashSet<>();
        String wanted = normalize(target);
        try (InputStream input = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, null);
            String channelId = "";
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "channel".equals(parser.getName())) {
                    channelId = parser.getAttributeValue(null, "id");
                } else if (event == XmlPullParser.START_TAG && "display-name".equals(parser.getName())) {
                    String name = parser.nextText();
                    String normalized = normalize(name);
                    if (normalized.equals(wanted)) exact.add(channelId);
                    else if (!wanted.isEmpty() && (normalized.contains(wanted) || wanted.contains(normalized))) {
                        fuzzy.add(channelId);
                    }
                }
            }
        }
        return exact.isEmpty() ? fuzzy : exact;
    }

    private static JSONArray readProgrammes(File file, Set<String> ids, String target, String day) throws Exception {
        Calendar startCalendar = Calendar.getInstance();
        Date parsedDay = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day);
        if (parsedDay != null) startCalendar.setTime(parsedDay);
        startCalendar.set(Calendar.HOUR_OF_DAY, 0);
        startCalendar.set(Calendar.MINUTE, 0);
        startCalendar.set(Calendar.SECOND, 0);
        startCalendar.set(Calendar.MILLISECOND, 0);
        long dayStart = startCalendar.getTimeInMillis();
        long dayEnd = dayStart + 24L * 60L * 60L * 1000L;
        JSONArray result = new JSONArray();
        try (InputStream input = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, null);
            long start = 0;
            long end = 0;
            boolean selected = false;
            String title = "";
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "programme".equals(parser.getName())) {
                    String channel = parser.getAttributeValue(null, "channel");
                    selected = ids.contains(channel) || (ids.isEmpty() && fuzzyMatch(channel, target));
                    if (selected) {
                        start = parseTime(parser.getAttributeValue(null, "start"));
                        end = parseTime(parser.getAttributeValue(null, "stop"));
                        title = "";
                    }
                } else if (selected && event == XmlPullParser.START_TAG && "title".equals(parser.getName())) {
                    title = parser.nextText();
                } else if (selected && event == XmlPullParser.END_TAG && "programme".equals(parser.getName())) {
                    if (!TextUtils.isEmpty(title) && start < dayEnd && end > dayStart) {
                        result.put(new JSONObject()
                                .put("title", title)
                                .put("start", formatTime(start))
                                .put("end", formatTime(end))
                                .put("startAt", start)
                                .put("endAt", end));
                    }
                    selected = false;
                }
            }
        }
        return result;
    }

    private static long parseTime(String value) {
        if (TextUtils.isEmpty(value) || value.length() < 14) return 0;
        try {
            String date = value.substring(0, 14);
            String tail = value.substring(14).trim();
            SimpleDateFormat format;
            if (tail.length() >= 5 && (tail.charAt(0) == '+' || tail.charAt(0) == '-')) {
                format = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
                return format.parse(date + " " + tail.substring(0, 5)).getTime();
            }
            format = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            format.setTimeZone(TimeZone.getDefault());
            return format.parse(date).getTime();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String formatTime(long value) {
        return value <= 0 ? "" : new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(value));
    }

    private static boolean fuzzyMatch(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        return a.equals(b) || (!a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a)));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.US)
                .replaceAll("[\\s_\\-·高清频道综合HD超清]+", "")
                .replace("中央电视台", "CCTV")
                .replace("央视", "CCTV");
    }

    private XmlTvEpg() {
    }
}
