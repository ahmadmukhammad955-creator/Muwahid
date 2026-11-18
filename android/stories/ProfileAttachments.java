package com.muwahid.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.util.Patterns;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;

/** Browse authorized chat history in pages, decrypting only on the device. */
final class ProfileAttachments {
    private final Activity activity;
    private final Api api;
    private final CryptoBox crypto;
    private final Executor executor;
    private final String chat, uid;
    private final ArrayList<String> labels = new ArrayList<>();
    private final ArrayList<Runnable> actions = new ArrayList<>();

    ProfileAttachments(Activity activity, Api api, CryptoBox crypto, Executor executor, String chat) {
        this.activity = activity;
        this.api = api;
        this.crypto = crypto;
        this.executor = executor;
        this.chat = chat;
        uid = api.uid();
    }

    private boolean active() {
        return !activity.isFinishing() && !activity.isDestroyed() && uid.equals(api.uid());
    }

    private void report(Exception e) {
        activity.runOnUiThread(() -> {
            if (active()) new AlertDialog.Builder(activity).setTitle("Медиа, файлы и ссылки")
                .setMessage(e.getMessage()).setPositiveButton("Закрыть", null).show();
        });
    }

    private String verifiedKey() throws Exception {
        Object key = api.rpc("mw_e2ee_peer_key", new JSONObject().put("p_chat", chat));
        if (!(key instanceof String) || !crypto.isPeerVerified(uid, chat, (String) key))
            throw new SecurityException("Сначала сверьте код безопасности в меню этого чата.");
        return (String) key;
    }

    void load(Long before) {
        android.app.ProgressDialog progress = android.app.ProgressDialog.show(activity, "", "Загрузка переписки…", true, true);
        executor.execute(() -> {
            try {
                String key = verifiedKey();
                JSONObject args = new JSONObject().put("p_chat", chat);
                if (before != null) args.put("p_before", before);
                JSONArray page = (JSONArray) api.rpc("mw_messages_get", args);
                long oldest = Long.MAX_VALUE;
                ArrayList<String> names = new ArrayList<>();
                ArrayList<Runnable> items = new ArrayList<>();
                for (int i = page.length() - 1; i >= 0; i--) {
                    JSONObject message = page.getJSONObject(i);
                    oldest = Math.min(oldest, message.getLong("id"));
                    String body = message.optString("body");
                    if (body.startsWith("mw1:")) body = crypto.decryptText(uid, chat, key, body);
                    JSONObject meta = null;
                    if (body.startsWith("{")) try { meta = new JSONObject(body); } catch (org.json.JSONException ignored) {}
                    if (meta != null && "media".equals(meta.optString("mw_kind"))) {
                        final JSONObject file = meta;
                        if (file.optString("path").startsWith(chat + "/")) {
                            names.add(file.optString("name", "Вложение"));
                            items.add(() -> openFile(file));
                        }
                    } else {
                        Matcher links = Patterns.WEB_URL.matcher(body);
                        while (links.find()) {
                            String url = links.group();
                            if (!url.toLowerCase(Locale.ROOT).matches("^https?://.*")) continue;
                            names.add(url);
                            items.add(() -> launch(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
                        }
                    }
                }
                final long cursor = oldest;
                activity.runOnUiThread(() -> {
                    if (!active() || !progress.isShowing()) return;
                    progress.dismiss();
                    labels.addAll(names);
                    actions.addAll(items);
                    AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("Медиа, файлы и ссылки");
                    if (labels.isEmpty()) dialog.setMessage(page.length() == 100 ?
                        "В этой части переписки вложений и ссылок нет." : "Вложений и ссылок нет.");
                    else dialog.setItems(labels.toArray(new String[0]), (d, i) -> actions.get(i).run());
                    if (page.length() == 100) dialog.setNeutralButton("Загрузить ещё", (d, w) -> load(cursor));
                    dialog.setNegativeButton("Закрыть", null).show();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> { if (active() && progress.isShowing()) { progress.dismiss(); report(e); } });
            }
        });
    }

    private void openFile(JSONObject meta) {
        executor.execute(() -> {
            try {
                String key = verifiedKey();
                byte[] bytes = crypto.decryptBytes(uid, chat, key, api.downloadMedia(meta.getString("path")));
                File dir = new File(activity.getCacheDir(), "media");
                File file = new File(dir, UUID.randomUUID() + "_" + meta.optString("name", "file").replaceAll("[^\\p{L}\\p{N}._ -]", "_"));
                try {
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Не удалось сохранить вложение");
                    try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
                } finally { Arrays.fill(bytes, (byte) 0); }
                Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", file);
                Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, meta.optString("mime", "application/octet-stream"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.runOnUiThread(() -> { if (active()) launch(open); });
            } catch (Exception e) { report(e); }
        });
    }

    private void launch(Intent intent) {
        try { activity.startActivity(intent); }
        catch (android.content.ActivityNotFoundException e) { report(new Exception("На телефоне нет приложения для открытия.")); }
    }
}
