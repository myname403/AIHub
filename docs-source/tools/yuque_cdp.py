import websocket, json, threading, urllib.request, urllib.parse, time, subprocess, sys, os, tempfile

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
URL = "https://www.yuque.com/zhangzhijun-91vgw/javaai1.0"
PASSWORD = "nxyt"
PORT = 9333

def log(*a):
    print(*a, flush=True)

# ---- launch chrome headless with remote debugging ----
user_data = tempfile.mkdtemp(prefix="ydb_")
args = [
    CHROME,
    "--headless=new",
    "--no-sandbox",
    "--disable-gpu",
    "--disable-dev-shm-usage",
    "--no-first-run",
    "--no-default-browser-check",
    f"--remote-debugging-port={PORT}",
    f"--user-data-dir={user_data}",
]
log("launching chrome ...")
proc = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def http_get(path):
    for _ in range(60):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}{path}", timeout=2) as r:
                return json.loads(r.read().decode())
        except Exception:
            time.sleep(0.5)
    raise RuntimeError("chrome debug endpoint not available")

# wait for endpoint
http_get("/json/version")
log("chrome up")

class CDP:
    def __init__(self, url):
        self.ws = websocket.create_connection(url, suppress_origin=True)
        self._id = 0
        self._lock = threading.Lock()
        self._pending = {}
        self._events = []
        self._closed = False
        threading.Thread(target=self._reader, daemon=True).start()
    def _reader(self):
        while True:
            try:
                raw = self.ws.recv()
            except Exception:
                self._closed = True
                break
            try:
                msg = json.loads(raw)
            except Exception:
                continue
            if "id" in msg and "method" not in msg:
                self._pending[msg["id"]] = msg
            else:
                self._events.append(msg)
    def send(self, method, params=None, timeout=30):
        with self._lock:
            self._id += 1
            mid = self._id
        self.ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        deadline = time.time() + timeout
        while time.time() < deadline:
            if mid in self._pending:
                return self._pending.pop(mid)
            time.sleep(0.05)
        raise TimeoutError(method)
    def listen(self, method, timeout=8):
        deadline = time.time() + timeout
        while time.time() < deadline:
            for e in list(self._events):
                if e.get("method") == method:
                    self._events.remove(e)
                    return e
            time.sleep(0.1)
        return None
    def eval(self, expr, timeout=30):
        r = self.send("Runtime.evaluate",
                      {"expression": expr, "returnByValue": True, "awaitPromise": True},
                      timeout=timeout)
        if "error" in r:
            raise RuntimeError(r["error"])
        return r.get("result", {}).get("result", {}).get("value")

# find the existing page target (launched as about:blank)
targets = http_get("/json")
page_target = None
for t in targets:
    if t.get("type") == "page" and "webSocketDebuggerUrl" in t:
        page_target = t
        break
if not page_target:
    raise RuntimeError("no page target found: " + json.dumps(targets)[:300])
ws_url = page_target["webSocketDebuggerUrl"]
log("target ws:", ws_url)
cdp = CDP(ws_url)
cdp.send("Page.enable")
cdp.send("Runtime.enable")
cdp.send("DOM.enable")
cdp.send("Page.navigate", {"url": URL})
for _ in range(40):
    ev = cdp.listen("Page.loadEventFired", timeout=2)
    if ev:
        break
log("page loaded (initial)")

time.sleep(2)

# diagnostic: inputs & buttons
diag = cdp.eval(r"""
(() => {
  const ins = Array.from(document.querySelectorAll('input')).map(i => ({tag:i.tagName,type:i.type,id:i.id,name:i.name,ph:i.placeholder,cls:i.className}));
  const btns = Array.from(document.querySelectorAll('button')).map(b => (b.innerText||'').trim().slice(0,30));
  const h1 = (document.querySelector('h1,h2,title')||{}).innerText || document.title;
  return JSON.stringify({title:document.title, h1, inputs:ins, buttons:btns, bodyLen:(document.body.innerText||'').length});
})()
""")
log("DIAG:", diag)

# enter password
cdp.eval(r"""
(() => {
  const el = document.querySelector('input[type=password]') || document.querySelector('input');
  if(!el){return 'NO_INPUT';}
  el.focus();
  el.click && el.click();
  return 'focused:'+(el.type||el.tagName);
})()
""")
cdp.send("Input.insertText", {"text": PASSWORD})
time.sleep(0.5)
# click submit button
clicked = cdp.eval(r"""
(() => {
  const cands = ['确认','进入','提交','确定','访问','验证'];
  const btn = Array.from(document.querySelectorAll('button')).find(b => cands.some(c => (b.innerText||'').includes(c)));
  if(btn){ btn.click(); return 'clicked:'+btn.innerText.trim(); }
  // fallback: any primary button
  const p = document.querySelector('button.primary,button[type=submit],.ant-btn-primary');
  if(p){ p.click(); return 'clicked-primary'; }
  return 'NO_BUTTON';
})()
""")
log("SUBMIT:", clicked)

# wait for content
time.sleep(4)
for _ in range(40):
    ev = cdp.listen("Page.loadEventFired", timeout=2)
    if ev:
        break
time.sleep(2)

content = cdp.eval(r"""
(() => {
  return (document.body.innerText||'').slice(0, 20000);
})()
""")
log("=== PAGE TEXT (first 8000) ===")
log(content[:8000])

# also dump doc tree / links if present
tree = cdp.eval(r"""
(() => {
  const links = Array.from(document.querySelectorAll('a[href]'))
     .map(a => ({t:(a.innerText||'').trim().slice(0,80), h:a.getAttribute('href')}))
     .filter(x => x.t && /yuque|doc/.test(x.h||''));
  return JSON.stringify(links.slice(0,60));
})()
""")
log("LINKS:", tree)

# save full text to file
out = r"D:\AiWorkSpace\2026-09-09-16-11-29\yuque_javaai1.0.txt"
with open(out, "w", encoding="utf-8") as f:
    f.write(content)
log("saved ->", out)

# screenshot
try:
    cdp.send("Page.captureScreenshot", {"format":"png","full":False}, timeout=20)
    ev = cdp.listen("Page.captureScreenshot", timeout=20)
    if ev and "params" in ev and "data" in ev["params"]:
        import base64
        png = r"D:\AiWorkSpace\2026-09-09-16-11-29\yuque_javaai1.0.png"
        with open(png,"wb") as f:
            f.write(base64.b64decode(ev["params"]["data"]))
        log("screenshot ->", png)
except Exception as e:
    log("screenshot failed:", e)

cdp.ws.close()
proc.terminate()
try: proc.wait(timeout=5)
except Exception: proc.kill()
log("done")
