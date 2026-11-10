#!/usr/bin/env python3
"""Regression guard for LIVE audio behavior in the packaged Muwahid WebView."""
from pathlib import Path
import re
import subprocess
import sys
import tempfile

source=Path(sys.argv[1]).read_text(encoding="utf-8")
required={
    "ICE retry queue": "pc._iceBatch=batch.concat(pc._iceBatch||[])",
    "signal acknowledged after success": "if(processed===false)break;",
    "remote media explicitly unmuted": "a.srcObject=remote;a.muted=false;a.volume=1;",
    "connection diagnostics": "pc.oniceconnectionstatechange",
    "no 90ms signaling flood": "return ready===ids.length?1000:220;",
    "WebView audio activation": "async function activateAudio()",
}
for name, text in required.items():
    if text not in source:
        raise SystemExit(f"FAIL: {name}")

inline_scripts=re.findall(r"<script(?:\s[^>]*)?>(.*?)</script>",source,re.S|re.I)
if not inline_scripts:
    raise SystemExit("FAIL: no inline LIVE scripts")
with tempfile.TemporaryDirectory() as temp:
    for index, code in enumerate(inline_scripts):
        js=Path(temp)/f"script_{index}.js"
        js.write_text(code,encoding="utf-8")
        subprocess.run(["node","--check",str(js)],check=True)
print(f"LIVE_AUDIO_STATIC_OK ({len(inline_scripts)} inline scripts)")
