#!/usr/bin/env python3
"""Disable the retired built-in Muva Cat pack in Android release builds.

Runs after android/family-security.patch on the version-0.13.37 source archive.
Keeps historical encrypted message data intact, but hides old Muva Cat artwork
in clients that receive this update. Never removes user-imported stickers.
"""
from pathlib import Path


def replace_one(path: str, old: str, new: str) -> None:
    file = Path(path)
    source = file.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one occurrence, found {count}: {old[:100]!r}")
    file.write_text(source.replace(old, new), encoding="utf-8")


CATALOG = "app/src/main/java/com/muwahid/app/StickerCatalog.java"
VIEW = "app/src/main/java/com/muwahid/app/AnimatedStickerView.java"
MAIN = "app/src/main/java/com/muwahid/app/MainActivity.java"
TEST = "app/src/androidTest/java/com/muwahid/app/SmokeInstrumentation.java"
GRADLE = "app/build.gradle"

replace_one(CATALOG,
    'public static final String[] PACKS={"Кот Мува","Облачко"};',
    'public static final String[] PACKS={"","Облачко"};')
replace_one(CATALOG,
    'private static void check(int id){if(id<0||id>=12)throw new IllegalArgumentException("Unknown sticker");}',
    'public static boolean isAvailable(int id){return id>=6 && id<12;}\n'
    '    private static void check(int id){if(!isAvailable(id))throw new IllegalArgumentException("Sticker removed or unknown");}')

replace_one(VIEW,
    'if(!meta.optBoolean("sticker",false)||id<0||id>=12)return -1;',
    'if(!meta.optBoolean("sticker",false)||!StickerCatalog.isAvailable(id))return -1;')

replace_one(MAIN,
    'for(int pack=0;pack<StickerCatalog.PACKS.length;pack++){',
    'for(int pack=1;pack<StickerCatalog.PACKS.length;pack++){')
replace_one(MAIN,
    'for(int pack=0;pack<2;pack++){',
    'for(int pack=1;pack<StickerCatalog.PACKS.length;pack++){')
replace_one(MAIN,
    'if(id>=0&&id<12&&prefs.getBoolean("sticker_pack_"+(id/6),true))ids.add(id);',
    'if(StickerCatalog.isAvailable(id)&&prefs.getBoolean("sticker_pack_"+(id/6),true))ids.add(id);')
replace_one(MAIN,
    'private void showStickerPack(int pack){',
    'private void showStickerPack(int pack){if(pack!=1)return;')
replace_one(MAIN,
    'private void previewSticker(int id,boolean send,Dialog picker){',
    'private void previewSticker(int id,boolean send,Dialog picker){if(!StickerCatalog.isAvailable(id))return;')
replace_one(MAIN,
    'private void sendSticker(int stickerId){',
    'private void sendSticker(int stickerId){if(!StickerCatalog.isAvailable(stickerId))return;')
replace_one(MAIN,
    'for(int id:ids){AnimatedStickerView iv=stickerImage(id);',
    'for(int id:ids){if(!StickerCatalog.isAvailable(id))continue;AnimatedStickerView iv=stickerImage(id);')
replace_one(MAIN,
    'if("image".equals(type)){if(meta.optBoolean("sticker",false))drawStickerMessage(meta,mine,when);else drawImageMessage(meta,mine,when);return;}',
    'if("image".equals(type)){if(meta.optBoolean("sticker",false)){int retiredId=meta.optInt("sticker_id",-1);'
    'if(retiredId>=0&&retiredId<6){bubble("Стикер удалён из Muwahid",mine,when);return;}'
    'drawStickerMessage(meta,mine,when);}else drawImageMessage(meta,mine,when);return;}')

replace_one(TEST,
    'for(String label:new String[]{"Кот Мува","Облачко","Стикеры и эмодзи"})if(findText(decor,label)==null)throw new AssertionError("Missing sticker pack: "+label);',
    'for(String label:new String[]{"Облачко","Стикеры и эмодзи"})if(findText(decor,label)==null)'
    'throw new AssertionError("Missing sticker pack: "+label);'
    'if(findText(decor,"Кот Мува")!=null)throw new AssertionError("Retired cat pack remains visible");')
replace_one(TEST, 'for(int id=0;id<12;id++){', 'for(int id=6;id<12;id++){')  # replaced one at a time below
replace_one(TEST, 'for(int id=0;id<12;id++){', 'for(int id=6;id<12;id++){')
replace_one(TEST, 'if(hashes.size()!=12)throw new AssertionError("Duplicate sticker artwork");',
    'if(hashes.size()!=6)throw new AssertionError("Duplicate sticker artwork");\n'
    '            if(StickerCatalog.isAvailable(0)||StickerCatalog.isAvailable(5)||!StickerCatalog.isAvailable(6))'
    'throw new AssertionError("Built-in cat sticker retirement failed");\n'
    '            try{StickerCatalog.png(0);throw new AssertionError("Retired cat can still be generated");}'
    'catch(IllegalArgumentException expected){}\n'
    '            org.json.JSONObject retired=new org.json.JSONObject().put("sticker",true)'
    '.put("sticker_id",0).put("sticker_animation","muwahid-vector-v1");\n'
    '            if(AnimatedStickerView.catalogId(retired)!=-1)throw new AssertionError("Retired cat can still animate");')
replace_one(TEST,
    'StickerCatalog.draw(c,0,frame*100);c.translate(256,0);StickerCatalog.draw(c,7,frame*100);',
    'StickerCatalog.draw(c,6,frame*100);c.translate(256,0);StickerCatalog.draw(c,7,frame*100);')
replace_one(TEST,
    '.put("sticker_id",0).put("sticker_animation","muwahid-vector-v1").put("name",StickerCatalog.name(0)+".png");',
    '.put("sticker_id",6).put("sticker_animation","muwahid-vector-v1").put("name",StickerCatalog.name(6)+".png");')

replace_one(GRADLE, "versionCode 73", "versionCode 74")
replace_one(GRADLE, "versionName '0.13.38'", "versionName '0.13.39'")
replace_one(TEST, '"0.13.38".equals(BuildConfig.VERSION_NAME)', '"0.13.39".equals(BuildConfig.VERSION_NAME)')
replace_one(TEST, "MW_SMOKE_OK: 0.13.38 ", "MW_SMOKE_OK: 0.13.39 ")

# Fail the release build if a new picker entry or test reintroduces this pack.
src = Path(MAIN).read_text(encoding="utf-8")
cat = Path(CATALOG).read_text(encoding="utf-8")
assert "Кот Мува" not in cat
assert 'for(int pack=0;pack<2;pack++)' not in src
assert 'if(id>=0&&id<12&&prefs.getBoolean("sticker_pack_"+(id/6),true))' not in src
assert 'bubble("Стикер удалён из Muwahid",mine,when)' in src
print("MUWAHID_MUVA_CAT_RETIRED: six built-in cat stickers disabled, historical messages hidden, cloud stickers untouched")
