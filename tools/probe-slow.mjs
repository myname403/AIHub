const base = process.argv[2] || 'http://localhost:18081'
const t0 = Date.now()
const res = await fetch(base + '/auth/probe/x', { method: 'POST', body: '' })
console.log(`[+${Date.now() - t0}ms] status=${res.status} transfer=${res.headers.get('transfer-encoding')}`)
const reader = res.body.getReader()
const dec = new TextDecoder()
for (;;) {
  const { done, value } = await reader.read()
  if (done) break
  console.log(`[+${Date.now() - t0}ms] chunk(${value.length}B): ${dec.decode(value).trim()}`)
}
console.log(`[+${Date.now() - t0}ms] done`)
