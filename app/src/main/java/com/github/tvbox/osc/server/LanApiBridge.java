package com.github.tvbox.osc.server;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class LanApiBridge {
    private static final long TIMEOUT_SECONDS = 45;
    private static final long SEARCH_TIMEOUT_SECONDS = 20;
    private static final int SEARCH_RESULT_LIMIT = 60;

    private final SourceViewModel sourceViewModel = new SourceViewModel();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object requestLock = new Object();
    private final Object progressLock = new Object();
    private final Context context;

    LanApiBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    JSONArray sites() throws JSONException {
        JSONArray result = new JSONArray();
        for (SourceBean source : ApiConfig.get().getSourceBeanList()) {
            if (source == null || !source.isSearchable() || TextUtils.isEmpty(source.getKey())) continue;
            JSONObject item = new JSONObject();
            item.put("key", source.getKey());
            item.put("name", source.getName());
            result.put(item);
        }
        return result;
    }

    JSONObject home() throws Exception {
        JSONObject result = catalog();
        result.put("history", history())
                .put("favorites", favorites())
                .put("searchHistory", searchHistory());
        return result;
    }

    JSONObject catalog() throws Exception {
        SourceBean source = requireHomeSource();
        AbsSortXml value = await(sourceViewModel.sortResult, () -> sourceViewModel.getSort(source.getKey()));
        JSONObject result = new JSONObject()
                .put("source", new JSONObject().put("key", source.getKey()).put("name", source.getName()))
                .put("categories", categories(value))
                .put("recommendations", videos(value == null ? null : value.videoList, source.getKey()));
        if (result.getJSONArray("recommendations").length() == 0 && value != null && value.list != null) {
            result.put("recommendations", videos(value.list.videoList, source.getKey()));
        }
        return result;
    }

    JSONObject library() throws JSONException {
        return new JSONObject()
                .put("history", history())
                .put("favorites", favorites())
                .put("searchHistory", searchHistory());
    }

    JSONObject category(String categoryId, int page) throws Exception {
        AbsSortXml sort = await(sourceViewModel.sortResult,
                () -> sourceViewModel.getSort(requireHomeSource().getKey()));
        MovieSort.SortData selected = null;
        if (sort != null && sort.classes != null && sort.classes.sortList != null) {
            for (MovieSort.SortData item : sort.classes.sortList) {
                if (item != null && categoryId.equals(item.id)) {
                    selected = item;
                    break;
                }
            }
        }
        if (selected == null) throw new IllegalArgumentException("未知分类");
        MovieSort.SortData target = selected;
        AbsXml value = await(sourceViewModel.listResult, () -> sourceViewModel.getList(target, Math.max(1, page)));
        Movie movie = value == null ? null : value.movie;
        return new JSONObject()
                .put("id", target.id)
                .put("name", safe(target.name))
                .put("page", movie == null ? page : movie.page)
                .put("pageCount", movie == null ? page : movie.pagecount)
                .put("items", videos(movie == null ? null : movie.videoList, requireHomeSource().getKey()));
    }

    JSONArray history() throws JSONException {
        JSONArray result = new JSONArray();
        for (VodInfo info : RoomDataManger.getAllVodRecord(100)) {
            JSONObject item = libraryItem(info.sourceKey, info.id, info.name, info.pic, info.note);
            item.put("episode", safe(info.playNote));
            item.put("flag", safe(info.playFlag));
            item.put("index", info.playIndex);
            item.put("position", savedProgress(info));
            item.put("duration", savedDuration(info));
            item.put("updatedAt", RoomDataManger.getVodRecordUpdateTime(info.sourceKey, info.id));
            result.put(item);
        }
        return result;
    }

    JSONArray favorites() throws JSONException {
        JSONArray result = new JSONArray();
        for (VodCollect info : RoomDataManger.getAllVodCollect()) {
            result.put(libraryItem(info.sourceKey, info.vodId, info.name, info.pic, "已收藏"));
        }
        return result;
    }

    JSONArray searchHistory() {
        JSONArray result = new JSONArray();
        ArrayList<String> values = Hawk.get(HawkConfig.SEARCH_HISTORY, new ArrayList<String>());
        for (String value : values) result.put(value);
        return result;
    }

    JSONObject live() throws Exception {
        JSONArray groups = new JSONArray();
        ArrayList<String> favorites = Hawk.get(HawkConfig.LAN_LIVE_FAVORITES, new ArrayList<String>());
        ArrayList<String> recents = Hawk.get(HawkConfig.LAN_LIVE_RECENTS, new ArrayList<String>());
        List<LiveChannelGroup> configured = ApiConfig.get().getChannelGroupList();
        if (configured != null) {
            for (int groupIndex = 0; groupIndex < configured.size(); groupIndex++) {
                LiveChannelGroup group = configured.get(groupIndex);
                if (group == null || !TextUtils.isEmpty(group.getGroupPassword())) continue;
                JSONArray channels = new JSONArray();
                List<LiveChannelItem> values = group.getLiveChannels();
                if (values != null) {
                    for (int channelIndex = 0; channelIndex < values.size(); channelIndex++) {
                        LiveChannelItem channel = values.get(channelIndex);
                        if (channel == null || channel.getSourceNum() <= 0) continue;
                        channels.put(new JSONObject()
                                .put("index", channelIndex)
                                .put("name", safe(channel.getChannelName()))
                                .put("number", channel.getChannelNum())
                                .put("logo", imageUrl(channel.getChannelLogo()))
                                .put("sourceCount", channel.getSourceNum())
                                .put("epgName", safe(channel.getChannelEpg()))
                                .put("favorite", favorites.contains(liveKey(group.getGroupName(), channel.getChannelName())))
                                .put("recent", recents.contains(liveKey(group.getGroupName(), channel.getChannelName()))));
                    }
                }
                if (channels.length() > 0) {
                    groups.put(new JSONObject()
                            .put("index", groupIndex)
                            .put("name", safe(group.getGroupName()))
                            .put("channels", channels));
                }
            }
        }
        return new JSONObject()
                .put("groups", groups)
                .put("lastChannel", Hawk.get(HawkConfig.LIVE_CHANNEL, ""))
                .put("lastGroup", Hawk.get(HawkConfig.LAN_LIVE_GROUP, 0))
                .put("lastSource", Hawk.get(HawkConfig.LAN_LIVE_SOURCE, 0));
    }

    JSONObject livePlay(int groupIndex, int channelIndex, int sourceIndex) throws Exception {
        LiveChannelItem channel = liveChannel(groupIndex, channelIndex);
        int selected = Math.max(0, Math.min(sourceIndex, channel.getSourceNum() - 1));
        String mediaUrl = channel.getChannelUrls().get(selected);
        if (!isHttpUrl(mediaUrl)) throw new IllegalStateException("直播地址不是 HTTP 媒体流");
        String proxyUrl = proxyUrl(mediaUrl, channel.getHeaders());
        List<LiveChannelGroup> groups = ApiConfig.get().getChannelGroupList();
        String groupName = groups.get(groupIndex).getGroupName();
        String key = liveKey(groupName, channel.getChannelName());
        ArrayList<String> recents = Hawk.get(HawkConfig.LAN_LIVE_RECENTS, new ArrayList<String>());
        recents.remove(key);
        recents.add(0, key);
        while (recents.size() > 20) recents.remove(recents.size() - 1);
        Hawk.put(HawkConfig.LAN_LIVE_RECENTS, recents);
        Hawk.put(HawkConfig.LIVE_CHANNEL, channel.getChannelName());
        Hawk.put(HawkConfig.LAN_LIVE_GROUP, groupIndex);
        Hawk.put(HawkConfig.LAN_LIVE_SOURCE, selected);
        return new JSONObject()
                .put("name", safe(channel.getChannelName()))
                .put("source", selected)
                .put("sourceCount", channel.getSourceNum())
                .put("proxyUrl", proxyUrl);
    }

    JSONObject setLiveFavorite(int groupIndex, int channelIndex, boolean favorite) throws Exception {
        LiveChannelItem channel = liveChannel(groupIndex, channelIndex);
        String groupName = ApiConfig.get().getChannelGroupList().get(groupIndex).getGroupName();
        String key = liveKey(groupName, channel.getChannelName());
        ArrayList<String> values = Hawk.get(HawkConfig.LAN_LIVE_FAVORITES, new ArrayList<String>());
        values.remove(key);
        if (favorite) values.add(0, key);
        Hawk.put(HawkConfig.LAN_LIVE_FAVORITES, values);
        return new JSONObject().put("favorite", favorite);
    }

    JSONObject epg(String channelName, String date) throws Exception {
        String day = TextUtils.isEmpty(date)
                ? new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) : date;
        String address = Hawk.get(HawkConfig.EPG_URL, "");
        if (TextUtils.isEmpty(address)) {
            address = "http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}";
        }
        if (address.toLowerCase(Locale.US).contains(".xml")) {
            return XmlTvEpg.load(context, address, channelName, day);
        }
        String encodedName = java.net.URLEncoder.encode(normalizeChannelName(channelName), "UTF-8")
                .replace("+", "%20");
        String url;
        if (address.contains("{name}") || address.contains("{date}")) {
            url = address.replace("{name}", encodedName).replace("{date}", day);
        } else {
            url = address + (address.contains("?") ? "&" : "?") + "ch=" + encodedName + "&date=" + day;
        }
        try (okhttp3.Response response = com.github.catvod.net.OkHttp.newCall(url).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("EPG 请求失败");
            }
            JSONObject body = new JSONObject(response.body().string());
            JSONArray input = body.optJSONArray("epg_data");
            if (input == null) input = body.optJSONArray("list");
            if (input == null) input = body.optJSONArray("data");
            JSONObject data = body.optJSONObject("data");
            if (input == null && data != null) {
                input = data.optJSONArray("epg_data");
                if (input == null) input = data.optJSONArray("list");
            }
            JSONArray items = new JSONArray();
            if (input != null) {
                for (int i = 0; i < input.length(); i++) {
                    JSONObject entry = input.optJSONObject(i);
                    if (entry == null) continue;
                    String title = entry.optString("title", entry.optString("name", ""));
                    String start = entry.optString("start", entry.optString("start_time", ""));
                    String end = entry.optString("end", entry.optString("end_time", ""));
                    if (!TextUtils.isEmpty(title)) {
                        long startAt = epgTime(day, start);
                        long endAt = epgTime(day, end);
                        if (endAt > 0 && startAt > 0 && endAt <= startAt) endAt += 24L * 60L * 60L * 1000L;
                        items.put(new JSONObject()
                                .put("title", title)
                                .put("start", start)
                                .put("end", end)
                                .put("startAt", startAt)
                                .put("endAt", endAt));
                    }
                }
            }
            return new JSONObject().put("date", day).put("items", items);
        }
    }

    JSONObject setFavorite(String sourceKey, String id, String title, String cover, boolean favorite)
            throws JSONException {
        if (favorite) requireSource(sourceKey);
        VodInfo info = new VodInfo();
        info.id = id;
        info.name = title;
        info.pic = cover;
        if (favorite) RoomDataManger.insertVodCollect(sourceKey, info);
        else RoomDataManger.deleteVodCollect(sourceKey, info);
        return new JSONObject().put("favorite", favorite);
    }

    JSONObject deleteHistory(String sourceKey, String id) throws JSONException {
        VodInfo info = new VodInfo();
        info.id = id;
        RoomDataManger.deleteVodRecord(sourceKey, info);
        return new JSONObject().put("deleted", true);
    }

    JSONObject saveProgress(String sourceKey, String id, String title, String cover, String flag,
                            int index, String episode, long position, long expectedRevision,
                            long duration, boolean completed) throws JSONException {
        requireSource(sourceKey);
        synchronized (progressLock) {
            long currentRevision = RoomDataManger.getVodRecordUpdateTime(sourceKey, id);
            if (expectedRevision >= 0 && currentRevision != expectedRevision) {
                return new JSONObject()
                        .put("saved", false)
                        .put("conflict", true)
                        .put("updatedAt", currentRevision);
            }
            VodInfo info = RoomDataManger.getVodInfo(sourceKey, id);
            if (info == null) info = new VodInfo();
            info.sourceKey = sourceKey;
            info.id = id;
            info.name = title;
            info.pic = cover;
            info.playFlag = flag;
            info.playIndex = Math.max(0, index);
            info.playNote = episode;
            String key = MD5.string2MD5(progressKey(info));
            RoomDataManger.insertVodRecord(sourceKey, info);
            if (completed) CacheManager.delete(key, 0L);
            else CacheManager.save(key, Math.max(0L, position));
            if (duration > 0) CacheManager.save(MD5.string2MD5(progressKey(info) + "-duration"), duration);
            return new JSONObject()
                    .put("saved", true)
                    .put("completed", completed)
                    .put("updatedAt", RoomDataManger.getVodRecordUpdateTime(sourceKey, id));
        }
    }

    JSONObject rememberSearch(String keyword) throws JSONException {
        if (!TextUtils.isEmpty(keyword)) HistoryHelper.setSearchHistory(keyword.trim());
        return new JSONObject().put("items", searchHistory());
    }

    JSONObject clearSearchHistory() throws JSONException {
        HistoryHelper.clearSearchHistory();
        return new JSONObject().put("items", new JSONArray());
    }

    JSONObject search(String sourceKey, String keyword) throws Exception {
        requireSource(sourceKey);
        AbsXml value = awaitSearch(sourceKey, keyword);
        JSONArray items = new JSONArray();
        if (value != null && value.movie != null && value.movie.videoList != null) {
            for (Movie.Video video : value.movie.videoList) {
                if (items.length() >= SEARCH_RESULT_LIMIT) break;
                items.put(item(video));
            }
        }
        return new JSONObject().put("items", items);
    }

    JSONObject detail(String sourceKey, String id) throws Exception {
        requireSource(sourceKey);
        AbsXml value = await(sourceViewModel.detailResult,
                () -> sourceViewModel.getDetail(sourceKey, id));
        Movie.Video video = firstVideo(value);
        if (video == null) throw new IllegalStateException("站点未返回详情");

        JSONObject result = item(video);
        result.put("description", safe(video.des));
        result.put("sourceKey", sourceKey);
        result.put("favorite", RoomDataManger.isVodCollect(sourceKey, id));
        JSONArray sources = new JSONArray();
        if (video.urlBean != null && video.urlBean.infoList != null) {
            for (Movie.Video.UrlBean.UrlInfo line : video.urlBean.infoList) {
                if (line == null || line.beanList == null || line.beanList.isEmpty()) continue;
                JSONObject source = new JSONObject();
                source.put("name", TextUtils.isEmpty(line.flag) ? "播放线路" : line.flag);
                JSONArray episodes = new JSONArray();
                for (Movie.Video.UrlBean.UrlInfo.InfoBean episode : line.beanList) {
                    if (episode == null || TextUtils.isEmpty(episode.url)) continue;
                    episodes.put(new JSONObject()
                            .put("name", TextUtils.isEmpty(episode.name) ? "播放" : episode.name)
                            .put("url", episode.url)
                            .put("flag", safe(line.flag)));
                }
                source.put("episodes", episodes);
                if (episodes.length() > 0) sources.put(source);
            }
        }
        result.put("sources", sources);
        VodInfo record = RoomDataManger.getVodInfo(sourceKey, id);
        if (record != null) {
            result.put("resume", new JSONObject()
                    .put("flag", safe(record.playFlag))
                    .put("index", record.playIndex)
                    .put("episode", safe(record.playNote))
                    .put("position", savedProgress(record))
                    .put("duration", savedDuration(record))
                    .put("updatedAt", RoomDataManger.getVodRecordUpdateTime(sourceKey, id)));
        }
        return result;
    }

    JSONObject play(String sourceKey, String flag, String url) throws Exception {
        requireSource(sourceKey);
        JSONObject value = await(sourceViewModel.playResult,
                () -> sourceViewModel.getPlay(sourceKey, safe(flag), "lan", url, ""));
        if (value == null) throw new IllegalStateException("站点未返回播放地址");

        String mediaUrl = firstUrl(value.optString("url", ""));
        boolean needsParse = "1".equals(value.optString("parse", "1"))
                || "1".equals(value.optString("jx", "0"));
        String playUrl = value.optString("playUrl", "");
        if (needsParse) {
            if (!TextUtils.isEmpty(playUrl) && playUrl.startsWith("http")) {
                mediaUrl = resolveJsonParser(playUrl + mediaUrl);
            } else {
                throw new IllegalStateException("此线路需要网页解析，当前 Web 播放器暂不支持");
            }
        } else {
            mediaUrl = playUrl + mediaUrl;
        }
        if (!isHttpUrl(mediaUrl)) throw new IllegalStateException("播放地址不是 HTTP 媒体流");

        JSONObject headers = value.optJSONObject("header");
        if (headers == null) headers = value.optJSONObject("headers");
        String proxyUrl = "/proxy?url=" + java.net.URLEncoder.encode(mediaUrl, "UTF-8");
        if (headers != null && headers.length() > 0) {
            String encoded = Base64.encodeToString(headers.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            proxyUrl += "&headers=" + java.net.URLEncoder.encode(encoded, "UTF-8");
        }
        String subtitle = value.optString("subt", "");
        return new JSONObject()
                .put("mode", "direct")
                .put("proxyUrl", proxyUrl)
                .put("subtitleUrl", isHttpUrl(subtitle)
                        ? "/proxy?url=" + java.net.URLEncoder.encode(subtitle, "UTF-8") : "");
    }

    private String resolveJsonParser(String target) throws Exception {
        try (okhttp3.Response response = com.github.catvod.net.OkHttp.newCall(target).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("解析线路请求失败");
            }
            JSONObject parsed = new JSONObject(response.body().string());
            String resolved = firstUrl(parsed.optString("url", ""));
            if (!isHttpUrl(resolved)) throw new IllegalStateException("解析线路未返回媒体地址");
            return resolved;
        }
    }

    private SourceBean requireSource(String sourceKey) {
        SourceBean source = ApiConfig.get().getSource(sourceKey);
        if (source == null || !source.isSearchable()) throw new IllegalArgumentException("未知站点");
        return source;
    }

    private JSONObject item(Movie.Video video) throws JSONException {
        return new JSONObject()
                .put("id", safe(video.id))
                .put("title", safe(video.name))
                .put("cover", imageUrl(video.pic))
                .put("coverRaw", safe(video.pic))
                .put("remark", safe(video.note));
    }

    private JSONArray categories(AbsSortXml value) throws JSONException {
        JSONArray result = new JSONArray();
        if (value == null || value.classes == null || value.classes.sortList == null) return result;
        for (MovieSort.SortData category : value.classes.sortList) {
            if (category == null || TextUtils.isEmpty(category.id)) continue;
            result.put(new JSONObject().put("id", category.id).put("name", safe(category.name)));
        }
        return result;
    }

    private JSONArray videos(List<Movie.Video> list, String sourceKey) throws JSONException {
        JSONArray result = new JSONArray();
        if (list == null) return result;
        for (Movie.Video video : list) {
            if (video == null || TextUtils.isEmpty(video.id)) continue;
            JSONObject item = item(video);
            item.put("sourceKey", TextUtils.isEmpty(video.sourceKey) ? sourceKey : video.sourceKey);
            result.put(item);
        }
        return result;
    }

    private JSONObject libraryItem(String sourceKey, String id, String title, String cover, String remark)
            throws JSONException {
        return new JSONObject()
                .put("sourceKey", safe(sourceKey))
                .put("id", safe(id))
                .put("title", safe(title))
                .put("cover", imageUrl(cover))
                .put("coverRaw", safe(cover))
                .put("remark", safe(remark))
                .put("available", ApiConfig.get().getSource(sourceKey) != null);
    }

    private SourceBean requireHomeSource() {
        SourceBean source = ApiConfig.get().getHomeSourceBean();
        if (source == null || TextUtils.isEmpty(source.getKey())) {
            throw new IllegalStateException("TVBox 尚未加载首页数据源");
        }
        return source;
    }

    private long savedProgress(VodInfo info) {
        Object value = CacheManager.getCache(MD5.string2MD5(progressKey(info)));
        return value instanceof Long ? (Long) value : value instanceof Integer ? ((Integer) value).longValue() : 0L;
    }

    private long savedDuration(VodInfo info) {
        Object value = CacheManager.getCache(MD5.string2MD5(progressKey(info) + "-duration"));
        return value instanceof Long ? (Long) value : value instanceof Integer ? ((Integer) value).longValue() : 0L;
    }

    private LiveChannelItem liveChannel(int groupIndex, int channelIndex) {
        List<LiveChannelGroup> groups = ApiConfig.get().getChannelGroupList();
        if (groups == null || groupIndex < 0 || groupIndex >= groups.size()) {
            throw new IllegalArgumentException("未知直播分组");
        }
        List<LiveChannelItem> channels = groups.get(groupIndex).getLiveChannels();
        if (channels == null || channelIndex < 0 || channelIndex >= channels.size()) {
            throw new IllegalArgumentException("未知直播频道");
        }
        return channels.get(channelIndex);
    }

    private String proxyUrl(String mediaUrl, Map<String, String> headerMap) throws Exception {
        String result = "/proxy?url=" + java.net.URLEncoder.encode(mediaUrl, "UTF-8");
        if (headerMap != null && !headerMap.isEmpty()) {
            JSONObject headers = new JSONObject(headerMap);
            String encoded = Base64.encodeToString(headers.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            result += "&headers=" + java.net.URLEncoder.encode(encoded, "UTF-8");
        }
        return result;
    }

    private String normalizeChannelName(String value) {
        if (value == null) return "";
        String compact = value.trim().replace("-", "").replace(" ", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)^(CCTV\\d+(?:\\+|K)?)(?:[\\u4e00-\\u9fa5].*|$)").matcher(compact);
        if (matcher.matches()) return matcher.group(1).toUpperCase(Locale.US);
        return value.trim();
    }

    private long epgTime(String day, String value) {
        if (TextUtils.isEmpty(value)) return 0L;
        String clean = value.trim();
        String[] patterns = clean.contains("-")
                ? new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"}
                : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"};
        String target = clean.contains("-") ? clean : day + " " + clean;
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.getDefault());
                format.setLenient(false);
                Date parsed = format.parse(target);
                if (parsed != null) return parsed.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private String liveKey(String group, String channel) {
        return safe(group) + "\n" + safe(channel);
    }

    private String progressKey(VodInfo info) {
        return safe(info.sourceKey) + safe(info.id) + safe(info.playFlag) + info.playIndex + safe(info.playNote);
    }

    private String imageUrl(String value) {
        if (!isHttpUrl(value)) return safe(value);
        try {
            return "/image?url=" + java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private Movie.Video firstVideo(AbsXml value) {
        List<Movie.Video> videos = value == null || value.movie == null ? null : value.movie.videoList;
        return videos == null || videos.isEmpty() ? null : videos.get(0);
    }

    private String firstUrl(String value) {
        if (TextUtils.isEmpty(value)) return "";
        try {
            JSONArray array = new JSONArray(value);
            if (array.length() >= 2) return array.optString(1, "");
        } catch (JSONException ignored) {
        }
        return value;
    }

    private boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private AbsXml awaitSearch(String sourceKey, String keyword) throws Exception {
        String token = "lan-" + sourceKey + "-" + System.nanoTime();
        SearchWaiter waiter = new SearchWaiter(sourceKey, token);
        EventBus.getDefault().register(waiter);
        try {
            sourceViewModel.getSearch(sourceKey, keyword, token);
            if (!waiter.completed.await(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("站点请求超时");
            }
            return waiter.result.get();
        } finally {
            EventBus.getDefault().unregister(waiter);
        }
    }

    private <T> T await(MutableLiveData<T> liveData, Runnable action) throws Exception {
        synchronized (requestLock) {
            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(1);
            AtomicBoolean armed = new AtomicBoolean(false);
            AtomicReference<T> result = new AtomicReference<>();
            Observer<T> observer = value -> {
                if (!armed.get()) return;
                result.set(value);
                completed.countDown();
            };
            mainHandler.post(() -> {
                liveData.observeForever(observer);
                armed.set(true);
                subscribed.countDown();
            });
            if (!subscribed.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("API 初始化超时");
            }
            try {
                action.run();
                if (!completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("站点请求超时");
                }
                return result.get();
            } finally {
                mainHandler.post(() -> liveData.removeObserver(observer));
            }
        }
    }

    public static final class SearchWaiter {
        private final String sourceKey;
        private final String token;
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicReference<AbsXml> result = new AtomicReference<>();

        SearchWaiter(String sourceKey, String token) {
            this.sourceKey = sourceKey;
            this.token = token;
        }

        @Subscribe(threadMode = ThreadMode.POSTING)
        public void onRefresh(RefreshEvent event) {
            if (event.type != RefreshEvent.TYPE_SEARCH_RESULT || !(event.obj instanceof AbsXml)) return;
            AbsXml value = (AbsXml) event.obj;
            if (!sourceKey.equals(value.sourceKey) || !token.equals(value.searchToken)) return;
            result.set(value);
            completed.countDown();
        }
    }
}
