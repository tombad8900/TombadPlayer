from flask import Flask, request, jsonify, render_template_string
import subprocess, os, shutil

app = Flask(__name__)
OUT_DIR = os.path.expanduser("~/TombadPlayer/data")
os.makedirs(OUT_DIR, exist_ok=True)

PAGE = """
<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TombadPlayer</title>
<style>
body{background:#111;color:#eee;font-family:sans-serif;padding:12px;max-width:640px;margin:auto}
input{width:100%;padding:10px;margin:4px 0;background:#222;color:#eee;border:1px solid #444;border-radius:6px;box-sizing:border-box}
button{padding:10px 14px;margin:3px;background:#2a6;color:#fff;border:0;border-radius:6px}
.row{display:flex;flex-wrap:wrap;align-items:center}
#delay{font-size:28px;text-align:center;margin:10px 0}
#msg{color:#fc6;margin-top:10px;white-space:pre-wrap}
</style>
</head>
<body>
<h2>TombadPlayer ∞</h2>
<label>رابط الصورة</label>
<input id="v" placeholder="الصق رابط الصورة">
<button onclick="swap()">⇄ تبديل</button>
<label>رابط الصوت</label>
<input id="a" placeholder="الصق رابط الصوت">
<h3>تزامن الصوت (+ = تأخير، − = تقديم)</h3>
<div id="delay">+0.000 ث</div>
<div class="row">
<button onclick="adj(-1)">−1s</button>
<button onclick="adj(-0.1)">−100ms</button>
<button onclick="adj(-0.01)">−10ms</button>
<button onclick="adj(0.01)">+10ms</button>
<button onclick="adj(0.1)">+100ms</button>
<button onclick="adj(1)">+1s</button>
<button onclick="reset()" style="background:#a33">0 تصفير</button>
</div>
<button onclick="play()" style="width:100%;margin-top:14px">▶ تشغيل المصدرين</button>
<div id="msg"></div>
<script>
let d=0;
const show=()=>document.getElementById('delay').textContent=(d>=0?'+':'−')+Math.abs(d).toFixed(3)+' ث';
function adj(x){d=Math.round((d+x)*1000)/1000;show()}
function reset(){d=0;show()}
function swap(){const v=document.getElementById('v'),a=document.getElementById('a');[v.value,a.value]=[a.value,v.value]}
async function play(){
 const m=document.getElementById('msg');m.textContent='جاري الدمج...';
 const r=await fetch('/merge',{method:'POST',headers:{'Content-Type':'application/json'},
  body:JSON.stringify({video:v.value,audio:a.value,delay:d})});
 const j=await r.json();m.textContent=j.message;
}
</script>
</body>
</html>
"""

@app.route("/")
def index():
    return render_template_string(PAGE)

@app.route("/merge", methods=["POST"])
def merge():
    data = request.get_json()
    video, audio = data.get("video","").strip(), data.get("audio","").strip()
    delay = float(data.get("delay", 0))
    if not video or not audio:
        return jsonify(message="لازم رابط الصورة ورابط الصوت")
    if not shutil.which("ffmpeg"):
        return jsonify(message="ffmpeg غير مثبت: pkg install ffmpeg")
    out = os.path.join(OUT_DIR, "merged.mp4")
    cmd = ["ffmpeg","-y","-i",video]
    if delay >= 0:
        cmd += ["-i",audio]
        af = ["-af", f"adelay={int(delay*1000)}:all=1"] if delay > 0 else []
    else:
        cmd += ["-ss",str(abs(delay)),"-i",audio]
        af = []
    cmd += ["-map","0:v:0","-map","1:a:0","-c:v","copy"] + af + ["-c:a","aac","-shortest",out]
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        return jsonify(message="فشل ffmpeg:\n" + p.stderr[-500:])
    return jsonify(message="تم الدمج: " + out)

if __name__ == "__main__":
    app.run(host="127.0.0.1", port=8080)
