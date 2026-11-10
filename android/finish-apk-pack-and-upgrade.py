#!/usr/bin/env python3
"""Finish 0.13.41: private APK imports, import progress and signed-upgrade test.

Runs after import-apk-sticker-packs.py and prepare-live-release.py.
No APK executable code is executed. Private user data is not wiped.
"""
from pathlib import Path

MAIN = Path("app/src/main/java/com/muwahid/app/MainActivity.java")
API = Path("app/src/main/java/com/muwahid/app/Api.java")
SMOKE = Path("app/src/androidTest/java/com/muwahid/app/SmokeInstrumentation.java")
UPGRADE = Path("app/src/androidTest/java/com/muwahid/app/UpgradeInstrumentation.java")
TEST_MANIFEST = Path("app/src/androidTest/AndroidManifest.xml")


def change_once(file, old, new, times=1):
    txt = file.read_text(encoding="utf-8")
    found = txt.count(old)
    if found != times:
        raise RuntimeError(f"Expected {times} occurrences in {file}, got {found}: {old[:110]!r}")
    file.write_text(txt.replace(old, new), encoding="utf-8")


# The DB checks source_kind values. Older 0.13.40 strings user_import,
# internet_url, received_chat and APK importer's "file" would violate
# mw_sticker_assets_source_kind_check, leaving apparently successful uploads
# with no library entries. Normalize before registering metadata.
change_once(MAIN,
    '.put("p_source_kind",sourceKind)',
    '.put("p_source_kind",'
    '("shared_app".equals(sourceKind)?"shared_app":'
    '("internet_url".equals(sourceKind)||"web_download".equals(sourceKind)?"web_download":'
    '("received_chat".equals(sourceKind)||"received_message".equals(sourceKind)?"received_message":"imported_file"))))')


# 240 stickers can require hundreds of network requests: show visible status
# rather than silently appearing frozen. The import runs off the UI thread.
change_once(MAIN,
    '.setPositiveButton("Импортировать",(d,w)->{\n              worker.execute(()->{',
    '.setPositiveButton("Импортировать",(d,w)->{\n'
    '               final TextView status=new TextView(this);\n'
    '               status.setText("Читаем APK и подготавливаем стикеры…");\n'
    '               status.setPadding(dp(20),dp(14),dp(20),dp(14));\n'
    '               final AlertDialog progress=new AlertDialog.Builder(this)\n'
    '                   .setTitle("Добавление целого набора").setView(status)\n'
    '                   .setCancelable(false).create();\n'
    '               progress.show();\n'
    '               worker.execute(()->{')
change_once(MAIN,
    'int imported=importStickerArchiveBlocking(archive);',
    'int imported=importStickerArchiveBlocking(archive,status);')
change_once(MAIN,
    'handler.post(()->{\n                          stickerCloudLoadedAt=0;',
    'handler.post(()->{\n                           progress.dismiss();\n                           stickerCloudLoadedAt=0;')
change_once(MAIN,
    '}catch(Exception e){handler.post(()->error(e));}\n              });',
    '}catch(Exception e){handler.post(()->{progress.dismiss();error(e);});}\n               });')
change_once(MAIN,
    'private int importStickerArchiveBlocking(Uri archive) throws Exception{',
    'private int importStickerArchiveBlocking(Uri archive,TextView status) throws Exception{')
change_once(MAIN,
    'imported++;\n                        importedBytes+=bytes.length;',
    'imported++;\n'
    '                         if(imported==1||imported%5==0){final int saved=imported;\n'
    '                             handler.post(()->status.setText("Сохранено "+saved+" стикеров…"));\n'
    '                         }\n'
    '                         importedBytes+=bytes.length;')


# Android Gradle Plugin generates a test instrumentation entry from
# testInstrumentationRunner=SmokeInstrumentation. The extra manifest injected
# by the LIVE release registered UpgradeInstrumentation instead and on signed
# release failed INSTRUMENTATION_FAILED. Use the *registered* smoke runner for
# both upgrade phases, without invoking the normal destructive smoke suite
# when verifying saved session data.
upgrade = UPGRADE.read_text(encoding="utf-8")
old = ('@Override public void onCreate(Bundle args){super.onCreate(args);phase=args.getString("phase");start();}\n'
       '    @Override public void onStart(){Bundle result=new Bundle();try{')
new = ('public static void runPhase(Instrumentation runner,String phase){Bundle result=new Bundle();try{')
assert upgrade.count(old) == 1, "Upgrade instrumentation source structure changed"
upgrade = upgrade.replace(old,new)
assert upgrade.count("Context ctx=getTargetContext();") == 1
upgrade = upgrade.replace("Context ctx=getTargetContext();", "Context ctx=runner.getTargetContext();")
assert upgrade.count("finish(Activity.RESULT_OK,result)") == 1
assert upgrade.count("finish(Activity.RESULT_CANCELED,result)") == 1
upgrade = upgrade.replace("finish(Activity.RESULT_OK,result)", "runner.finish(Activity.RESULT_OK,result)")
upgrade = upgrade.replace("finish(Activity.RESULT_CANCELED,result)", "runner.finish(Activity.RESULT_CANCELED,result)")
UPGRADE.write_text(upgrade, encoding="utf-8")

change_once(SMOKE,
    '    private Activity activity;',
    '    private Activity activity;\n    private String upgradePhase;')
change_once(SMOKE,
    '        super.onCreate(args);\n        start();',
    '        super.onCreate(args);\n'
    '        upgradePhase=args==null?null:args.getString("phase");\n'
    '        start();')
change_once(SMOKE,
    '    @Override public void onStart() {\n        Bundle result = new Bundle();',
    '    @Override public void onStart() {\n'
    '        if(upgradePhase!=null){UpgradeInstrumentation.runPhase(this,upgradePhase);return;}\n'
    '        Bundle result = new Bundle();')
# Rely on AGP's known-good generated instrumentation manifest, not a second
# unregistered upgrade test entry.
TEST_MANIFEST.unlink(missing_ok=True)


# Avoid implementation-specific text in ordinary UI (server remains Supabase).
copy = {
    "Загрузить изменения из Supabase":"Обновить мои наборы и стикеры",
    "Каталог Supabase временно недоступен. Встроенные наборы остаются доступны.":"Не удалось загрузить ваши наборы. Проверьте подключение к интернету.",
    "Каталог реакций загружается из Supabase":"Включить реакции на сообщения",
    "Загрузка наборов Supabase…":"Загрузка ваших наборов…",
    "В Supabase пока нет наборов. Нажмите «Создать набор».":"Пока нет наборов. Нажмите «Добавить мои стикеры».",
    "Загрузка стикеров Supabase…":"Загрузка ваших стикеров…",
    "Supabase временно недоступен. Встроенные стикеры работают.":"Не удалось загрузить стикеры. Проверьте подключение к интернету.",
    "Введите короткое имя. Оно будет храниться вместе со стикером в Supabase.":"Введите короткое имя, чтобы находить этот эмодзи.",
    "Muwahid · настройки обновляются через Supabase":"Muwahid · общение с близкими",
    "Google сейчас не активен на живом Auth-сервере Supabase. Проверьте Client ID, Client Secret и Save в провайдере Google.":"Вход через Google временно недоступен. Выберите другой способ входа.",
    "LIVE временно отключён через Supabase.":"Прямые эфиры сейчас временно недоступны.",
    "Раздел администраторов временно отключён через Supabase.":"Раздел администраторов сейчас временно недоступен.",
}
for old,new in copy.items():
    change_once(MAIN,old,new,2 if old=="LIVE временно отключён через Supabase." else 1)
errors={
    "Google не вернул данные входа. Проверьте Redirect URL в Supabase.":"Не удалось завершить вход через Google. Попробуйте ещё раз.",
    "Supabase отклонил OTP для этого номера. Код Muwahid уже разрешает создание аккаунта; проверьте состояние Phone Auth на сервере.":"Не удалось отправить код подтверждения. Попробуйте позже.",
    "Google-вход ещё не активен на сервере Supabase, хотя настройка может отображаться включённой.":"Вход через Google временно недоступен. Выберите другой способ входа.",
}
for old,new in errors.items():
    # The LIVE security patch may already have replaced an error message.
    if old in API.read_text(encoding='utf-8'):
        change_once(API,old,new)


src=MAIN.read_text(encoding="utf-8")
assert "private int importStickerArchiveBlocking(Uri archive,TextView status)" in src
assert 'status.setText("Сохранено "+saved+" стикеров…")' in src
assert '"imported_file"))))' in src
assert "UpgradeInstrumentation.runPhase(this,upgradePhase)" in SMOKE.read_text(encoding="utf-8")
assert not TEST_MANIFEST.exists()
print("MUWAHID_APK_PACK_FINAL: valid source_kind, visible import progress, upgrade session test and clean UI copy")
