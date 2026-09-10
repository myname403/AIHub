# -*- coding: utf-8 -*-
"""批量抓取语雀知识库：解锁密码后遍历所有文档，导出正文到 docs-source/yuque/"""
import websocket, json, threading, urllib.request, time, subprocess, os, re, tempfile

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
BASE = "https://www.yuque.com/zhangzhijun-91vgw/javaai1.0"
PASSWORD = "nxyt"
PORT = 9345
OUT_DIR = r"D:\AiWorkSpace\2026-09-09-16-11-29\docs-source\yuque"

os.makedirs(OUT_DIR, exist_ok=True)


def log(*a):
    print(*a, flush=True)


def sanitize(name, maxlen=80):
    name = re.sub(r'[\\/:*?"<>|\r\n\t]', "_", name).strip()
    return (name[:maxlen] or "untitled")


# ---------- launch chrome ----------
profile = tempfile.mkdtemp(prefix="yuque_scrape_")
proc = subprocess.Popen([
    CHROME, "--headless=new", "--no-sandbox", "--disable-gpu",
    "--disable-dev-shm-usage", "--no-first-run", "--no-default-browser-check",
    f"--remote-debugging-port={PORT}", f"--user-data-dir={profile}",
], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def http_get(path, timeout=60):
    for _ in range(int(timeout / 0.5)):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}{path}", timeout=2) as r:
                return json.loads(r.read().decode())
        except Exception:
            time.sleep(0.5)
    raise RuntimeError("chrome debug endpoint not available")


class CDP:
    def __init__(self, url):
        self.ws = websocket.create_connection(url, suppress_origin=True)
        self._id = 0
        self._pending = {}
        self._events = []
        self._lock = threading.Lock()
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self):
        while True:
            try:
                raw = self.ws.recv()
            except Exception:
                break
            try:
                m = json.loads(raw)
            except Exception:
                continue
            if "id" in m and "method" not in m:
                self._pending[m["id"]] = m
            else:
                self._events.append(m)

    def send(self, method, params=None, timeout=45):
        with self._lock:
            self._id += 1
            mid = self._id
        self.ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        dl = time.time() + timeout
        while time.time() < dl:
            if mid in self._pending:
                return self._pending.pop(mid)
            time.sleep(0.05)
        raise TimeoutError(method)

    def listen(self, method, timeout=8):
        dl = time.time() + timeout
        while time.time() < dl:
            for e in list(self._events):
                if e.get("method") == method:
                    self._events.remove(e)
                    return e
            time.sleep(0.1)
        return None

    def ev(self, expr, timeout=45):
        r = self.send("Runtime.evaluate",
                      {"expression": expr, "returnByValue": True, "awaitPromise": True},
                      timeout=timeout)
        if "error" in r:
            raise RuntimeError(str(r["error"])[:200])
        return r.get("result", {}).get("result", {}).get("value")

    def goto(self, url, wait=5):
        self.send("Page.navigate", {"url": url})
        for _ in range(60):
            if self.listen("Page.loadEventFired", timeout=2):
                break
        time.sleep(wait)


try:
    http_get("/json/version")
    log("[ok] chrome started")

    tgt = [t for t in http_get("/json") if t.get("type") == "page"
           and "webSocketDebuggerUrl" in t][0]
    cdp = CDP(tgt["webSocketDebuggerUrl"])
    cdp.send("Page.enable")
    cdp.send("Runtime.enable")

    # ---------- 1. 打开首页并解锁 ----------
    cdp.goto(BASE, wait=4)
    if "输入密码" in (cdp.ev("document.body.innerText") or ""):
        log("[..] 密码页，输入密码解锁")
        cdp.ev("document.querySelector('input')?.focus()")
        cdp.send("Input.insertText", {"text": PASSWORD})
        time.sleep(3)
        for _ in range(20):
            if cdp.listen("Page.loadEventFired", timeout=2):
                break
        time.sleep(3)
    body = cdp.ev("document.body.innerText") or ""
    log("[ok] 解锁状态：", "已解锁" if "输入密码" not in body else "仍在密码页")

    # 保存首页
    with open(os.path.join(OUT_DIR, "00-知识库首页.txt"), "w", encoding="utf-8") as f:
        f.write(body)
    log("[save] 知识库首页")

    # ---------- 2. 收集文档链接 ----------
    links = cdp.ev(r"""
(() => {
  const set = new Map();
  document.querySelectorAll('a[href]').forEach(a => {
    const h = a.getAttribute('href') || '';
    if (h && h.includes('/zhangzhijun-91vgw/javaai1.0/')) {
      const slug = h.split('?')[0].replace(/\/$/, '').split('/').pop();
      const t = (a.innerText || '').trim();
      if (slug && slug !== 'javaai1.0' && !set.has(slug)) set.set(slug, t || slug);
    }
  });
  return JSON.stringify([...set.entries()].map(([slug, title]) => ({slug, title})));
})()
""")
    docs = json.loads(links) if links else []
    log(f"[ok] 发现 {len(docs)} 篇文档")

    # ---------- 3. 逐篇抓取 ----------
    EXTRACT = r"""
(() => {
  // 滚动到底触发懒加载
  for (let i = 0; i < 25; i++) {
    window.scrollTo(0, document.body.scrollHeight);
  }
  const sels = ['.lake-content', '.doc-content', '#doc-content', 'article',
                '.ne-viewer-body', '.content', 'main'];
  let text = '';
  for (const s of sels) {
    const el = document.querySelector(s);
    if (el && (el.innerText || '').trim().length > text.length) text = el.innerText;
  }
  if (!text || text.length < 200) text = document.body.innerText;
  const title = (document.querySelector('h1') || {}).innerText || document.title || '';
  return JSON.stringify({title: String(title).trim(), text: String(text || '').trim()});
})()
"""
    saved = []
    for i, d in enumerate(docs, 1):
        url = f"{BASE}/{d['slug']}"
        try:
            cdp.goto(url, wait=4)
            time.sleep(1)
            raw = cdp.ev(EXTRACT, timeout=40)
            data = json.loads(raw) if raw else {}
            text = data.get("text", "")
            title = sanitize(data.get("title") or d.get("title") or d["slug"])
            if len(text) < 50:
                log(f"[{i}/{len(docs)}] 跳过（内容过短 {len(text)}）: {d['slug']}")
                continue
            fname = f"{i:02d}-{title}.txt"
            path = os.path.join(OUT_DIR, fname)
            with open(path, "w", encoding="utf-8") as f:
                f.write(f"# {title}\nURL: {url}\n\n{text}")
            saved.append((fname, len(text)))
            log(f"[{i}/{len(docs)}] {fname}  ({len(text)} 字)")
        except Exception as e:
            log(f"[{i}/{len(docs)}] 失败 {d['slug']}: {type(e).__name__} {e}")

    log("\n===== 抓取完成 =====")
    log(f"共保存 {len(saved)} 篇：")
    for fn, n in saved:
        log(f"  - {fn} ({n} 字)")

    cdp.ws.close()
finally:
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except Exception:
        proc.kill()
    log("browser closed")
