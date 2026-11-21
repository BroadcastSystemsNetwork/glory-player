package com.bsn.gloryplayer;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Base64;
import android.widget.EditText;
import androidx.core.app.ActivityCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@CapacitorPlugin(name = "AndroidFS")
public class AndroidFSPlugin extends Plugin {
    private final Map<String, File> handleMap = new HashMap<>();
    private int nextHandleId = 1;
    private MediaPlayer mediaPlayer = null;
    private boolean isPrepared = false;

    private synchronized String registerFile(File f) {
        String id = "h_" + (nextHandleId++);
        handleMap.put(id, f);
        return id;
    }

    private void releasePlayer() {
        isPrepared = false;
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            } catch (Exception ignored) {}
            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    @Override
    protected void handleOnDestroy() {
        releasePlayer();
        super.handleOnDestroy();
    }

    @PluginMethod
    public void playNative(PluginCall call) {
        String handleId = call.getString("handleId", "");
        String uriPath = call.getString("uri", "");
        int startMs = call.getInt("startMs", 0);

        File target = handleMap.get(handleId);
        if (target == null && !uriPath.isEmpty()) {
            target = new File(uriPath);
        }
        if (target == null || !target.isFile()) {
            call.reject("Audio file not found");
            return;
        }

        final File audioFile = target;
        getActivity().runOnUiThread(() -> {
            try {
                releasePlayer();
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                );
                try (FileInputStream fis = new FileInputStream(audioFile)) {
                    mediaPlayer.setDataSource(fis.getFD());
                }
                mediaPlayer.setOnPreparedListener(mp -> {
                    isPrepared = true;
                    mp.start();
                    if (startMs > 0 && startMs < mp.getDuration()) {
                        mp.seekTo(startMs);
                    }
                    JSObject ret = new JSObject();
                    ret.put("ok", true);
                    ret.put("durationMs", mp.getDuration());
                    call.resolve(ret);
                });
                mediaPlayer.setOnCompletionListener(mp -> {
                    JSObject ev = new JSObject();
                    notifyListeners("nativeEnded", ev);
                });
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    isPrepared = false;
                    call.reject("MediaPlayer error: " + what + "/" + extra);
                    return true;
                });
                mediaPlayer.prepareAsync();
            } catch (Exception e) {
                releasePlayer();
                call.reject("Playback failed: " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void pauseNative(PluginCall call) {
        try {
            if (mediaPlayer != null && isPrepared && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
        } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void resumeNative(PluginCall call) {
        try {
            if (mediaPlayer != null && isPrepared && !mediaPlayer.isPlaying()) {
                mediaPlayer.start();
            }
        } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void stopNative(PluginCall call) {
        try {
            if (mediaPlayer != null && isPrepared) {
                if (mediaPlayer.isPlaying()) mediaPlayer.pause();
                mediaPlayer.seekTo(0);
            }
        } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void seekNative(PluginCall call) {
        int posMs = call.getInt("positionMs", 0);
        try {
            if (mediaPlayer != null && isPrepared) {
                mediaPlayer.seekTo(Math.max(0, posMs));
            }
        } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void getNativeStatus(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            if (mediaPlayer != null && isPrepared) {
                ret.put("playing", mediaPlayer.isPlaying());
                ret.put("currentMs", mediaPlayer.getCurrentPosition());
                ret.put("durationMs", mediaPlayer.getDuration());
                call.resolve(ret);
                return;
            }
        } catch (Exception ignored) {}
        ret.put("playing", false);
        ret.put("currentMs", 0);
        ret.put("durationMs", 0);
        call.resolve(ret);
    }

    @PluginMethod
    public void pickDirectory(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(getActivity())
                    .setTitle("Storage Permission Needed")
                    .setMessage("Allow Glory Player to access your folders so you can open music and save recordings.")
                    .setPositiveButton("Grant Access", (d, w) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                            getActivity().startActivity(intent);
                        } catch (Exception e) {
                            try {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                                getActivity().startActivity(intent);
                            } catch (Exception ignored) {
                                showFolderDialog(call, Environment.getExternalStorageDirectory());
                                return;
                            }
                        }
                        call.reject("Grant permission and tap Open Folders again");
                    })
                    .setNegativeButton("Cancel", (d, w) -> call.reject("Canceled"))
                    .show();
                return;
            }

            ActivityCompat.requestPermissions(getActivity(), new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_MEDIA_AUDIO
            }, 1002);

            showFolderDialog(call, Environment.getExternalStorageDirectory());
        });
    }

    private void showFolderDialog(PluginCall call, File currentDir) {
        File root = Environment.getExternalStorageDirectory();
        File dir = (currentDir != null && currentDir.exists() && currentDir.isDirectory()) ? currentDir : root;

        List<File> subDirs = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory() && !c.getName().startsWith(".")) subDirs.add(c);
            }
            subDirs.sort(Comparator.comparing(f -> f.getName().toLowerCase()));
        }

        List<String> labels = new ArrayList<>();
        boolean hasParent = dir.getParentFile() != null && !dir.equals(root);
        if (hasParent) labels.add(".. (Up one level)");
        for (File sd : subDirs) labels.add(sd.getName());

        AlertDialog dialog = new AlertDialog.Builder(getActivity())
            .setTitle("Folder: " + dir.getName())
            .setItems(labels.toArray(new String[0]), (dlg, which) -> {
                if (hasParent && which == 0) {
                    showFolderDialog(call, dir.getParentFile());
                } else {
                    int idx = hasParent ? which - 1 : which;
                    showFolderDialog(call, subDirs.get(idx));
                }
            })
            .setPositiveButton("Use This Folder", (dlg, which) -> {
                JSObject ret = new JSObject();
                ret.put("handleId", registerFile(dir));
                ret.put("name", dir.getName());
                ret.put("uri", dir.getAbsolutePath());
                call.resolve(ret);
            })
            .setNeutralButton("New Folder", (dlg, which) -> {
                EditText input = new EditText(getActivity());
                input.setHint("Folder name");
                new AlertDialog.Builder(getActivity())
                    .setTitle("Create Folder in " + dir.getName())
                    .setView(input)
                    .setPositiveButton("Create", (d2, w2) -> {
                        String name = input.getText().toString().trim();
                        if (!name.isEmpty()) {
                            File created = new File(dir, name);
                            created.mkdirs();
                            showFolderDialog(call, created.exists() ? created : dir);
                        } else {
                            showFolderDialog(call, dir);
                        }
                    })
                    .setNegativeButton("Cancel", (d2, w2) -> showFolderDialog(call, dir))
                    .show();
            })
            .setNegativeButton("Cancel", (dlg, which) -> call.reject("Canceled"))
            .setCancelable(false)
            .create();
        dialog.show();
        if (dialog.getListView() != null) {
            int maxH = (int) (getActivity().getResources().getDisplayMetrics().heightPixels * 0.52);
            dialog.getListView().getLayoutParams().height = maxH;
            dialog.getListView().requestLayout();
        }
    }

    @PluginMethod
    public void listEntries(PluginCall call) {
        String handleId = call.getString("handleId", "");
        File dir = handleMap.get(handleId);
        JSArray arr = new JSArray();
        if (dir != null && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(f -> f.getName().toLowerCase()));
                for (File child : files) {
                    if (child.getName().startsWith(".")) continue;
                    JSObject obj = new JSObject();
                    obj.put("handleId", registerFile(child));
                    obj.put("name", child.getName());
                    obj.put("kind", child.isDirectory() ? "directory" : "file");
                    obj.put("uri", child.getAbsolutePath());
                    arr.put(obj);
                }
            }
        }
        JSObject ret = new JSObject();
        ret.put("items", arr);
        call.resolve(ret);
    }

    @PluginMethod
    public void readFileBase64(PluginCall call) {
        String handleId = call.getString("handleId", "");
        File file = handleMap.get(handleId);
        JSObject ret = new JSObject();
        if (file == null || !file.isFile()) {
            ret.put("data", "");
            call.resolve(ret);
            return;
        }
        try (InputStream is = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            ret.put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
        } catch (Exception e) {
            ret.put("data", "");
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void writeFileBase64(PluginCall call) {
        String dirHandleId = call.getString("dirHandleId", "");
        String fileName = call.getString("fileName", "recording.webm");
        String base64Data = call.getString("base64Data", "");
        File dir = handleMap.get(dirHandleId);
        JSObject ret = new JSObject();
        if (dir == null || !dir.isDirectory()) {
            ret.put("handleId", "");
            call.resolve(ret);
            return;
        }
        try {
            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
            File target = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(target)) {
                fos.write(bytes);
                fos.flush();
            }
            ret.put("handleId", registerFile(target));
            ret.put("uri", target.getAbsolutePath());
            call.resolve(ret);
            return;
        } catch (Exception ignored) {}
        ret.put("handleId", "");
        call.resolve(ret);
    }

    @PluginMethod
    public void removeEntry(PluginCall call) {
        String dirHandleId = call.getString("dirHandleId", "");
        String entryName = call.getString("entryName", "");
        File dir = handleMap.get(dirHandleId);
        boolean ok = false;
        if (dir != null && dir.isDirectory()) {
            File target = new File(dir, entryName);
            ok = target.exists() && target.delete();
        }
        JSObject ret = new JSObject();
        ret.put("ok", ok);
        call.resolve(ret);
    }
}