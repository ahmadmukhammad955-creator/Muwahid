#!/usr/bin/env python3
"""Keep infrastructure names out of Muwahid's user-facing Android interface.

Runs after family-security.patch, remove-cat-stickers.py and
finish-personal-stickers.py on the stable Android source archive.
"""
from pathlib import Path

MAIN=Path("app/src/main/java/com/muwahid/app/MainActivity.java")
API=Path("app/src/main/java/com/muwahid/app/Api.java")
GRADLE=Path("app/build.gradle")
TEST=Path("app/src/androidTest/java/com/muwahid/app/SmokeInstrumentation.java")

def replace_exact(file,old,new,count=1):
    data=file.read_text(encoding="utf-8")
    if data.count(old)!=count:
        raise RuntimeError(f"{file}: expected {count} occurrence(s), got {data.count(old)}: {old[:75]!r}")
    file.write_text(data.replace(old,new),encoding="utf-8")

# Local fallbacks and error banners should refer to product features, not backend infrastructure.
messages={
    "Загрузить изменения из Supabase":"Обновить мои наборы и стикеры",
    "Каталог Supabase временно недоступен. Встроенные наборы остаются доступны.":"Не удалось загрузить ваши наборы. Проверьте подключение к интернету.",
    "Каталог реакций загружается из Supabase":"Включить реакции на сообщения",
    "Загрузка наборов Supabase…":"Загрузка ваших наборов…",
    "В Supabase пока нет наборов. Нажмите «Создать набор».":"Пока нет наборов. Нажмите «Добавить мои стикеры».",
    "Загрузка стикеров Supabase…":"Загрузка ваших стикеров…",
    "Supabase временно недоступен. Встроенные стикеры работают.":"Не удалось загрузить стикеры. Проверьте подключение к интернету.",
    "Введите короткое имя. Оно будет храниться вместе со стикером в Supabase.":"Введите короткое имя, чтобы легко находить этот эмодзи.",
    "Muwahid · настройки обновляются через Supabase":"Muwahid · общение с близкими",
    "Google сейчас не активен на живом Auth-сервере Supabase. Проверьте Client ID, Client Secret и Save в провайдере Google.":"Вход через Google сейчас недоступен. Попробуйте другой способ входа.",
    "LIVE временно отключён через Supabase.":"Прямые эфиры сейчас временно недоступны.",
    "Раздел администраторов временно отключён через Supabase.":"Раздел администраторов сейчас временно недоступен.",
}
for old,new in messages.items():
    replace_exact(MAIN,old,new,2 if old=="LIVE временно отключён через Supabase." else 1)

api_errors={
    "Google не вернул данные входа. Проверьте Redirect URL в Supabase.":"Не удалось завершить вход через Google. Попробуйте ещё раз.",
    "Supabase отклонил OTP для этого номера. Код Muwahid уже разрешает создание аккаунта; проверьте состояние Phone Auth на сервере.":"Не удалось отправить код подтверждения. Попробуйте позже.",
    "Google-вход ещё не активен на сервере Supabase, хотя настройка может отображаться включённой.":"Вход через Google сейчас недоступен. Попробуйте другой способ входа.",
}
for old,new in api_errors.items():
    replace_exact(API,old,new)

replace_exact(GRADLE,"versionCode 75","versionCode 76")
replace_exact(GRADLE,"versionName '0.13.40'","versionName '0.13.41'")
replace_exact(TEST,'"0.13.40".equals(BuildConfig.VERSION_NAME)','"0.13.41".equals(BuildConfig.VERSION_NAME)')
replace_exact(TEST,"MW_SMOKE_OK: 0.13.40 ","MW_SMOKE_OK: 0.13.41 ")

# Prevent future regressions: no visible string literal may leak backend's name.
for f in (MAIN,API):
    source=f.read_text(encoding="utf-8")
    for line in source.splitlines():
        if 'Supabase' in line and not line.lstrip().startswith('//'):
            # Allow actual API metadata and source-kind tags, but not visible strings.
            if ('source_kind' in line or 'supabase' in line.lower() and '"Supabase' not in line and 'Supabase.' not in line):
                continue
            raise RuntimeError(f"Remaining possible user-facing infrastructure text in {f}: {line[:160]}")

print("MUWAHID_UI_TEXT_CLEAN: no Supabase instructions in visible welcome, auth, stickers, LIVE, or admin labels")
