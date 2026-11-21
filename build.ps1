cd C:\GloryPlayerApp
copy "C:\Users\ChauncyGardiner\Desktop\Glory Player\Glory Player.html" "C:\GloryPlayerApp\www\index.html" -Force

$javaCode = @'
package com.bsn.gloryplayer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import com.getcapacitor.BridgeActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends BridgeActivity {
    private static final int REQ_OPEN_TREE = 4001;
    private static final int REQ_RECORD_AUDIO = 4002;
    private String pendingCallbackId = null;
    private final Map<String, DocumentFile> handleMap = new HashMap<>();
    private int handleCounter = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        }

        WebView webView = getBridge().getWebView();
        webView.addJavascriptInterface(new NativeFSBridge(), "AndroidFS");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });
    }

    private String registerHandle(DocumentFile doc) {
        String id = "h_" + (handleCounter++);
        handleMap.put(id, doc);
        return id;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_TREE && pendingCallbackId != null) {
            String cbId = pendingCallbackId;
            pendingCallbackId = null;
            WebView webView = getBridge().getWebView();

            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                try {
                    getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                } catch (Exception ignored) {}

                DocumentFile rootDoc = DocumentFile.fromTreeUri(this, treeUri);
                if (rootDoc != null) {
                    String hid = registerHandle(rootDoc);
                    String safeName = JSONObject.quote(rootDoc.getName() != null ? rootDoc.getName() : "Folder");
                    String safeUri = JSONObject.quote(treeUri.toString());
                    webView.post(() -> webView.evaluateJavascript(
                        "window.__resolveAndroidDir(" + JSONObject.quote(cbId) + ", " + JSONObject.quote(hid) + ", " + safeName + ", " + safeUri + ");", null));
                    return;
                }
            }
            webView.post(() -> webView.evaluateJavascript(
                "window.__rejectAndroidDir(" + JSONObject.quote(cbId) + ");", null));
        }
    }

    public class NativeFSBridge {
        @JavascriptInterface
        public void pickDirectory(String callbackId) {
            pendingCallbackId = callbackId;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQ_OPEN_TREE);
        }

        @JavascriptInterface
        public String listEntries(String handleId) {
            try {
                DocumentFile dir = handleMap.get(handleId);
                JSONArray arr = new JSONArray();
                if (dir != null && dir.isDirectory()) {
                    for (DocumentFile child : dir.listFiles()) {
                        String name = child.getName();
                        if (name == null) continue;
                        JSONObject obj = new JSONObject();
                        obj.put("name", name);
                        obj.put("kind", child.isDirectory() ? "directory" : "file");
                        obj.put("handleId", registerHandle(child));
                        obj.put("uri", child.getUri().toString());
                        arr.put(obj);
                    }
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String readFileBase64(String handleId) {
            try {
                DocumentFile file = handleMap.get(handleId);
                if (file == null) return "";
                InputStream is = getContentResolver().openInputStream(file.getUri());
                if (is == null) return "";
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[16384];
                int nRead;
                while ((nRead = is.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                is.close();
                return Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String writeFileBase64(String dirHandleId, String fileName, String mimeType, String base64Data) {
            try {
                DocumentFile dir = handleMap.get(dirHandleId);
                if (dir == null) return "";
                DocumentFile existing = dir.findFile(fileName);
                if (existing != null) existing.delete();
                DocumentFile created = dir.createFile(mimeType != null && !mimeType.isEmpty() ? mimeType : "audio/webm", fileName);
                if (created == null) return "";
                OutputStream os = getContentResolver().openOutputStream(created.getUri());
                if (os == null) return "";
                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                os.write(bytes);
                os.flush();
                os.close();
                return registerHandle(created);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean removeEntry(String dirHandleId, String name) {
            try {
                DocumentFile dir = handleMap.get(dirHandleId);
                if (dir == null) return false;
                DocumentFile target = dir.findFile(name);
                return target != null && target.delete();
            } catch (Exception e) {
                return false;
            }
        }
    }
}
'@

Set-Content -Path "C:\GloryPlayerApp\android\app\src\main\java\com\bsn\gloryplayer\MainActivity.java" -Value $javaCode -Encoding UTF8

$shim = @'
<script>
if (window.AndroidFS && !window.showDirectoryPicker) {
  const __dirResolvers = {};
  let __cbCounter = 1;

  window.__resolveAndroidDir = (cbId, handleId, name, uri) => {
    if (__dirResolvers[cbId]) {
      __dirResolvers[cbId].resolve(makeAndroidDirHandle(handleId, name, uri));
      delete __dirResolvers[cbId];
    }
  };
  window.__rejectAndroidDir = (cbId) => {
    if (__dirResolvers[cbId]) {
      const err = new Error("User aborted");
      err.name = "AbortError";
      __dirResolvers[cbId].reject(err);
      delete __dirResolvers[cbId];
    }
  };

  function makeAndroidFileHandle(handleId, name, uri) {
    return {
      kind: "file",
      name,
      _handleId: handleId,
      _uri: uri,
      async isSameEntry(other) { return other && other._uri === uri; },
      async getFile() {
        const b64 = window.AndroidFS.readFileBase64(handleId);
        const bin = atob(b64);
        const bytes = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        return new File([bytes], name);
      }
    };
  }

  function makeAndroidDirHandle(handleId, name, uri) {
    return {
      kind: "directory",
      name,
      _handleId: handleId,
      _uri: uri,
      async isSameEntry(other) { return other && other._uri === uri; },
      async *entries() {
        const raw = window.AndroidFS.listEntries(handleId);
        const items = JSON.parse(raw || "[]");
        for (const item of items) {
          if (item.kind === "directory") {
            yield [item.name, makeAndroidDirHandle(item.handleId, item.name, item.uri)];
          } else {
            yield [item.name, makeAndroidFileHandle(item.handleId, item.name, item.uri)];
          }
        }
      },
      async getFileHandle(fileName) {
        return {
          kind: "file",
          name: fileName,
          async createWritable() {
            let chunks = [];
            return {
              async write(blob) { chunks.push(blob); },
              async close() {
                const fullBlob = new Blob(chunks);
                const buf = await fullBlob.arrayBuffer();
                const bytes = new Uint8Array(buf);
                let bin = "";
                for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
                const b64 = btoa(bin);
                const newId = window.AndroidFS.writeFileBase64(handleId, fileName, fullBlob.type || "audio/webm", b64);
                if (!newId) throw new Error("Failed to write file to Android storage.");
              }
            };
          }
        };
      },
      async removeEntry(entryName) {
        const ok = window.AndroidFS.removeEntry(handleId, entryName);
        if (!ok) throw new Error("Could not delete " + entryName);
      }
    };
  }

  window.showDirectoryPicker = () => new Promise((resolve, reject) => {
    const cbId = "cb_" + (__cbCounter++);
    __dirResolvers[cbId] = { resolve, reject };
    window.AndroidFS.pickDirectory(cbId);
  });
}
</script>
'@

$htmlPath = "C:\GloryPlayerApp\www\index.html"
$html = Get-Content -Path $htmlPath -Raw
$html = $html -replace "(<script>)", "$shim`r`n`$1"
Set-Content -Path $htmlPath -Value $html -Encoding UTF8

npx cap sync android
cd C:\GloryPlayerApp\android
.\gradlew.bat assembleDebug