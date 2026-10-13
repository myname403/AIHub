const token = process.argv[2];
const base = process.argv[3] || 'http://localhost:8080';
const t0 = Date.now();
const res = await fetch(base + '/api/ai/chat/ndjson', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token, 'X-Tenant-Id': '1001', 'X-User-Id': '1' },
  body: JSON.stringify({ appId: 9001, conversationId: '', message: '用三句话介绍一下Java语言', scene: 'chat' })
});
console.log(`[+${Date.now() - t0}ms] status=${res.status} encoding=${res.headers.get('content-encoding')} transfer=${res.headers.get('transfer-encoding')}`);
const reader = res.body.getReader();
const dec = new TextDecoder();
for (;;) {
  const { done, value } = await reader.read();
  if (done) break;
  const txt = dec.decode(value, { stream: true }).replace(/\s+/g, ' ').slice(0, 70);
  console.log(`[+${Date.now() - t0}ms] chunk(${value.length}B): ${txt}`);
}
console.log(`[+${Date.now() - t0}ms] done`);
