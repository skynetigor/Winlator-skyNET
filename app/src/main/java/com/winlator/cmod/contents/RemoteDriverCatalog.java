package com.winlator.cmod.contents;

import android.content.Context;
import android.net.Uri;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Adrenotools drivers listed in the remote contents.json as entries of type "RendererDriver". */
public final class RemoteDriverCatalog {
    private RemoteDriverCatalog() {}

    private static final String TYPE_NAME = "RendererDriver";
    private static final Pattern GITHUB_REPO = Pattern.compile("^https://github\\.com/([^/]+/[^/]+)/");

    public static final class Entry {
        public final String id;
        public final String repository;
        public final String name;
        public final String url;
        /** "stable", "prerelease" or null. */
        public final String channel;

        Entry(String id, String repository, String name, String url, String channel) {
            this.id = id;
            this.repository = repository;
            this.name = name;
            this.url = url;
            this.channel = channel;
        }
    }

    /** Downloads the registry and returns its drivers in registry order. Blocking; call off the main thread. */
    public static List<Entry> load(Context context) {
        ArrayList<Entry> result = new ArrayList<>();
        String registryUrl = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES);
        try (Response response = new OkHttpClient().newCall(new Request.Builder().url(registryUrl).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return result;
            JSONArray entries = new JSONArray(response.body().string());
            for (int i = 0; i < entries.length(); i++) {
                JSONObject object = entries.optJSONObject(i);
                if (object == null || !TYPE_NAME.equalsIgnoreCase(object.optString("type"))) continue;
                String url = object.optString("remoteUrl", "");
                if (url.isEmpty()) continue;
                String verName = object.optString("verName", "");
                String name = object.optString("name", "").trim();
                if (name.isEmpty()) name = verName;
                if (name.isEmpty()) continue;
                String source = object.optString("source", "");
                if (source.isEmpty()) {
                    Matcher m = GITHUB_REPO.matcher(url);
                    source = m.find() ? m.group(1) : "";
                }
                result.add(new Entry(object.optString("id", url), source, name, url,
                        ContentsManager.normalizeChannel(object.optString("channel", ""))));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static String install(Context context, String url) {
        File archive = new File(context.getCacheDir(), "winz-driver-" + System.nanoTime() + ".zip");
        try (Response response = new OkHttpClient().newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return "";
            try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(archive)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
            AdrenotoolsManager manager = new AdrenotoolsManager(context);
            String installedId = manager.installDriver(Uri.fromFile(archive));
            if (installedId != null && !installedId.isEmpty()) manager.setDriverSourceUrl(installedId, url);
            return installedId;
        } catch (Exception ignored) {
            return "";
        } finally {
            archive.delete();
        }
    }
}
