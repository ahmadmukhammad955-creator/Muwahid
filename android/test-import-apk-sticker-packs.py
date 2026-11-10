#!/usr/bin/env python3
"""Regression test: APK pack importer is injected into Android source and manifest.

This is a fast source integration test; Gradle build and emulator tests run later
in the GitHub Actions workflow. No downloaded APK is executed.
"""
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("import-apk-sticker-packs.py")

def main():
    with tempfile.TemporaryDirectory(prefix="muwahid-apk-stickers-") as root:
        root = Path(root)
        java = root / "app/src/main/java/com/muwahid/app/MainActivity.java"
        manifest = root / "app/src/main/AndroidManifest.xml"
        java.parent.mkdir(parents=True)
        manifest.parent.mkdir(parents=True, exist_ok=True)
        java.write_text(
            'class MainActivity {\n'
            '  void chooseStickerImportTarget(){\n'
            '    new String[]{"Добавить из телефона","Добавить по ссылке из интернета"},\n'
            '    else showStickerUrlDialog();})\n'
            '  }\n'
            '  void onActivityResult(int requestCode,int resultCode,Object data){\n'
            '    else if(requestCode==REQ_STICKER_IMPORT&&resultCode==RESULT_CANCELED){pendingStickerPackId="";}\n'
            '  }\n'
            '    private void launchStickerImportPicker(){}\n'
            '}\n',
            encoding="utf-8",
        )
        manifest.write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            '<application><activity android:name=".MainActivity" android:exported="true">'
            '</activity></application></manifest>',
            encoding="utf-8",
        )
        result = subprocess.run([sys.executable, str(SCRIPT)], cwd=root,
                                capture_output=True, text=True)
        if result.returncode:
            raise AssertionError("Importer exited with error:\n" + result.stdout + result.stderr)
        j = java.read_text(encoding="utf-8")
        x = manifest.read_text(encoding="utf-8")
        for expected in (
            "Импортировать набор из APK", "importStickerArchiveBlocking(Uri archive)",
            "assets/contents.json", "new java.util.zip.ZipFile(tmp)",
            "importStickerBytesBlocking(bytes,file", "p_is_public\",false",
            "ACTION_OPEN_DOCUMENT", "onNewIntent(Intent intent)",
            "processIncomingStickerArchive", "getCacheDir()",
        ):
            assert expected in j, f"Android import missing {expected!r}"
        for expected in ("android.intent.action.VIEW", "android.intent.action.SEND",
                         "application/vnd.android.package-archive",
                         "application/zip", "Добавить стикеры в Muwahid"):
            assert expected in x, f"Manifest missing {expected!r}"
        assert j.count("importStickerArchiveBlocking(Uri archive)")==1
        assert "PackageInstaller" not in j
        assert "REQUEST_INSTALL_PACKAGES" not in x
        print("APK_PACK_IMPORT_TEST_OK: whole packs, private uploads, VIEW/SEND, no APK execution")

if __name__ == "__main__":
    main()
