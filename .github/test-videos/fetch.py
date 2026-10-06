"""Fetch freely licensed test videos (Wikimedia Commons) for BlueShield quality checks.

Runs on GitHub Actions (open internet). For every search query, takes the first few videos under a size
limit, cuts a short clip (720p max, no audio), and writes clips + a manifest with source URL, author and
license to OUT_DIR. Only Creative Commons / public-domain files are kept.
"""
import json, os, subprocess, sys, urllib.parse, urllib.request

OUT = sys.argv[1]
QUERIES = sys.argv[2].split("|")
PER_QUERY = int(sys.argv[3]) if len(sys.argv) > 3 else 2
API = "https://commons.wikimedia.org/w/api.php"
UA = {"User-Agent": "BlueShield-test-fetch/1.0 (github actions; test clips)"}
os.makedirs(OUT, exist_ok=True)

def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read()

manifest = []
for q in QUERIES:
    params = {"action": "query", "format": "json", "generator": "search", "gsrnamespace": 6, "gsrlimit": 25,
              "gsrsearch": f"filetype:video {q}", "prop": "imageinfo",
              "iiprop": "url|size|mime|extmetadata", "iiurlwidth": 0}
    data = json.loads(get(API + "?" + urllib.parse.urlencode(params)))
    pages = sorted(data.get("query", {}).get("pages", {}).values(), key=lambda p: p.get("index", 0))
    taken = 0
    for p in pages:
        if taken >= PER_QUERY:
            break
        ii = (p.get("imageinfo") or [{}])[0]
        meta = ii.get("extmetadata", {})
        lic = meta.get("LicenseShortName", {}).get("value", "")
        if not any(k in lic for k in ("CC", "Public domain", "PD")):
            continue
        if ii.get("size", 10 ** 12) > 120 * 1024 * 1024:
            continue
        name = f"{len(manifest):02d}_" + "".join(c if c.isalnum() else "_" for c in q)[:30]
        src = os.path.join(OUT, name + ".src")
        try:
            open(src, "wb").write(get(ii["url"]))
            dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", src],
                                       capture_output=True, text=True).stdout.strip() or 0)
            start = max(0.0, min(dur / 3, dur - 10))
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{start:.2f}", "-i", src, "-t", "8", "-an",
                            "-vf", "scale='min(1280,iw)':-2", "-c:v", "libx264", "-crf", "26", "-pix_fmt", "yuv420p",
                            os.path.join(OUT, name + ".mp4")], check=True)
        except Exception as e:  # noqa: BLE001
            print("skip", p.get("title"), e)
            continue
        finally:
            if os.path.exists(src):
                os.remove(src)
        manifest.append({"file": name + ".mp4", "title": p.get("title"), "url": ii.get("descriptionurl"),
                         "license": lic, "author": meta.get("Artist", {}).get("value", "")[:200]})
        taken += 1
        print("ok", name, p.get("title"), lic)
json.dump(manifest, open(os.path.join(OUT, "manifest.json"), "w"), indent=1, ensure_ascii=False)
print(len(manifest), "clips")
